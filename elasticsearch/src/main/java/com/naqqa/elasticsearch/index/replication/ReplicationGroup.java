package com.naqqa.elasticsearch.index.replication;

import com.naqqa.elasticsearch.cluster.routing.ShardId;
import com.naqqa.elasticsearch.cluster.state.IndexMetadata;
import com.naqqa.elasticsearch.common.json.JsonValue;
import com.naqqa.elasticsearch.index.engine.DeleteOperation;
import com.naqqa.elasticsearch.index.engine.DeleteResult;
import com.naqqa.elasticsearch.index.engine.IndexOperation;
import com.naqqa.elasticsearch.index.engine.IndexResult;
import com.naqqa.elasticsearch.index.engine.NoOpOperation;
import com.naqqa.elasticsearch.index.engine.NoOpResult;
import com.naqqa.elasticsearch.index.seqno.GlobalCheckpointTracker;
import com.naqqa.elasticsearch.index.seqno.SequenceNumbers;
import com.naqqa.elasticsearch.index.shard.IndexShard;
import com.naqqa.elasticsearch.index.translog.Operation;
import com.naqqa.elasticsearch.index.translog.VersionType;
import com.naqqa.elasticsearch.transport.Connection;
import com.naqqa.elasticsearch.transport.ConnectionProfile;
import com.naqqa.elasticsearch.transport.TransportChannel;
import com.naqqa.elasticsearch.transport.TransportException;
import com.naqqa.elasticsearch.transport.TransportRequestOptions;
import com.naqqa.elasticsearch.transport.TransportResponseHandler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class ReplicationGroup {

    public static final String REPLICATE_ACTION = "internal:replication/op";
    private static final long DEFAULT_REPLICA_TIMEOUT_MILLIS = 10_000L;

    private final Object mutex = new Object();
    private final ShardId shardId;
    private final ReplicaFailureListener failureListener;
    private final GlobalCheckpointTracker checkpointTracker = new GlobalCheckpointTracker();
    private final Map<String, ShardCopy> inSyncReplicas = new LinkedHashMap<>();
    private final Map<String, ShardCopy> initializingReplicas = new LinkedHashMap<>();
    private final Map<String, ShardCopy> failedReplicas = new LinkedHashMap<>();
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private volatile long replicaTimeoutMillis = DEFAULT_REPLICA_TIMEOUT_MILLIS;
    private volatile ShardCopy primary;

    public ReplicationGroup(ShardId shardId, ShardCopy primary, ReplicaFailureListener failureListener) {
        this.shardId = shardId;
        this.primary = primary;
        this.failureListener = failureListener;
        primary.setRole(ShardCopy.Role.PRIMARY);
        checkpointTracker.markAllocationIdAsInSync(primary.allocationId());
    }

    public static ReplicationGroup fromClusterState(IndexMetadata indexMetadata, ShardId shardId, ShardCopy primary,
                                                      List<ShardCopy> candidateReplicas, ReplicaFailureListener failureListener) {
        ReplicationGroup group = new ReplicationGroup(shardId, primary, failureListener);
        java.util.Set<String> inSyncIds = indexMetadata.inSyncAllocationIds(shardId.id());
        for (ShardCopy replica : candidateReplicas) {
            if (inSyncIds.contains(replica.allocationId())) {
                group.addInSyncReplica(replica);
            } else {
                group.addInitializingReplica(replica);
            }
        }
        return group;
    }

    public void withReplicaTimeoutMillis(long millis) {
        this.replicaTimeoutMillis = millis;
    }

    public ShardId shardId() {
        return shardId;
    }

    public ShardCopy primary() {
        return primary;
    }

    public GlobalCheckpointTracker checkpointTracker() {
        return checkpointTracker;
    }

    public List<ShardCopy> inSyncReplicasSnapshot() {
        synchronized (mutex) {
            return new ArrayList<>(inSyncReplicas.values());
        }
    }

    public List<ShardCopy> initializingReplicasSnapshot() {
        synchronized (mutex) {
            return new ArrayList<>(initializingReplicas.values());
        }
    }

    public List<ShardCopy> failedReplicasSnapshot() {
        synchronized (mutex) {
            return new ArrayList<>(failedReplicas.values());
        }
    }

    public void addInSyncReplica(ShardCopy replica) {
        replica.setRole(ShardCopy.Role.IN_SYNC_REPLICA);
        registerHandler(replica);
        synchronized (mutex) {
            initializingReplicas.remove(replica.allocationId());
            failedReplicas.remove(replica.allocationId());
            inSyncReplicas.put(replica.allocationId(), replica);
        }
        checkpointTracker.markAllocationIdAsInSync(replica.allocationId());
    }

    public void addInitializingReplica(ShardCopy replica) {
        replica.setRole(ShardCopy.Role.INITIALIZING);
        registerHandler(replica);
        synchronized (mutex) {
            initializingReplicas.put(replica.allocationId(), replica);
        }
    }

    public void markInitializingReplicaInSync(String allocationId) {
        ShardCopy replica;
        synchronized (mutex) {
            replica = initializingReplicas.get(allocationId);
        }
        if (replica == null) {
            throw new IllegalStateException("no initializing replica with allocation id [" + allocationId + "]");
        }
        addInSyncReplica(replica);
    }

    void markReplicaFailed(String allocationId) {
        ShardCopy removed;
        synchronized (mutex) {
            removed = inSyncReplicas.remove(allocationId);
            if (removed != null) {
                failedReplicas.put(allocationId, removed);
            }
        }
        if (removed != null) {
            checkpointTracker.removeAllocationId(allocationId);
            connections.remove(allocationId);
        }
    }

    void recordPrimaryCheckpoint(long seqNo) {
        checkpointTracker.updateLocalCheckpoint(primary.allocationId(), seqNo);
    }

    void recordReplicaCheckpoint(String allocationId, long seqNo) {
        checkpointTracker.updateLocalCheckpoint(allocationId, seqNo);
    }

    long replicaTimeoutMillis() {
        return replicaTimeoutMillis;
    }

    ReplicaFailureListener failureListener() {
        return failureListener;
    }

    Connection connectionTo(ShardCopy replica) {
        return connections.computeIfAbsent(replica.allocationId(),
            id -> primary.transportService().connectToNode(replica.node(), ConnectionProfile.buildDefault()));
    }

    private void invalidateAllConnections() {
        for (Connection connection : connections.values()) {
            try {
                connection.close();
            } catch (Exception ignored) {
            }
        }
        connections.clear();
    }

    private void registerHandler(ShardCopy replica) {
        replica.transportService().registerRequestHandler(REPLICATE_ACTION, ReplicationRequest::new,
            (request, channel) -> handleIncomingReplication(replica, request, channel));
    }

    private void handleIncomingReplication(ShardCopy replica, ReplicationRequest request, TransportChannel channel) {
        try {
            replica.primaryContext().assertNotStale(request.primaryTerm());
        } catch (StalePrimaryException e) {
            sendError(channel, e);
            return;
        }
        try {
            ReplicationResponse response = applyLocally(replica.indexShard(), request);
            channel.sendResponse(response);
        } catch (Exception e) {
            sendError(channel, e);
        }
    }

    private static void sendError(TransportChannel channel, Exception e) {
        try {
            channel.sendResponse(e);
        } catch (IOException ignored) {
        }
    }

    static ReplicationResponse applyLocally(IndexShard shard, ReplicationRequest request) throws IOException {
        if (request.hasExplicitSeqNo()) {
            return applyAtSeqNo(shard, request);
        }
        switch (request.opType()) {
            case INDEX: {
                Map<String, Object> sourceMap = decodeSource(request.source());
                IndexResult result = shard.index(IndexOperation.of(request.id(), request.routing(), sourceMap));
                if (!result.success()) {
                    throw asIOException(result.failure());
                }
                return new ReplicationResponse(result.seqNo(), result.primaryTerm(), result.version(), shard.localCheckpoint());
            }
            case DELETE: {
                DeleteResult result = shard.delete(DeleteOperation.of(request.id()));
                if (!result.success()) {
                    throw asIOException(result.failure());
                }
                return new ReplicationResponse(result.seqNo(), result.primaryTerm(), result.version(), shard.localCheckpoint());
            }
            case NOOP: {
                NoOpResult result = shard.noOp(request.reason());
                if (!result.success()) {
                    throw asIOException(result.failure());
                }
                return new ReplicationResponse(result.seqNo(), result.primaryTerm(), 0L, shard.localCheckpoint());
            }
            default:
                throw new IOException("unsupported replication op type [" + request.opType() + "]");
        }
    }

    static ReplicationResponse applyAtSeqNo(IndexShard shard, ReplicationRequest request) throws IOException {
        long seqNo = request.seqNo();
        long opTerm = request.opPrimaryTerm();
        switch (request.opType()) {
            case INDEX: {
                Map<String, Object> sourceMap = decodeSource(request.source());
                IndexOperation op = new IndexOperation(request.id(), request.routing(), sourceMap, request.version(),
                    VersionType.EXTERNAL, SequenceNumbers.UNASSIGNED_SEQ_NO, SequenceNumbers.UNASSIGNED_PRIMARY_TERM);
                IndexResult result = shard.indexAtSeqNo(op, seqNo, opTerm);
                if (!result.success()) {
                    throw asIOException(result.failure());
                }
                return new ReplicationResponse(result.seqNo(), result.primaryTerm(), result.version(), shard.localCheckpoint());
            }
            case DELETE: {
                DeleteOperation op = new DeleteOperation(request.id(), request.version(), VersionType.EXTERNAL,
                    SequenceNumbers.UNASSIGNED_SEQ_NO, SequenceNumbers.UNASSIGNED_PRIMARY_TERM);
                DeleteResult result = shard.deleteAtSeqNo(op, seqNo, opTerm);
                if (!result.success()) {
                    throw asIOException(result.failure());
                }
                return new ReplicationResponse(result.seqNo(), result.primaryTerm(), result.version(), shard.localCheckpoint());
            }
            case NOOP: {
                NoOpResult result = shard.noOpAtSeqNo(request.reason(), seqNo, opTerm);
                if (!result.success()) {
                    throw asIOException(result.failure());
                }
                return new ReplicationResponse(result.seqNo(), result.primaryTerm(), 0L, shard.localCheckpoint());
            }
            default:
                throw new IOException("unsupported replication op type [" + request.opType() + "]");
        }
    }

    private static IOException asIOException(Exception failure) {
        if (failure instanceof IOException io) {
            return io;
        }
        return new IOException("replica apply failed", failure);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> decodeSource(byte[] bytes) {
        return (Map<String, Object>) JsonValue.parse(bytes).toJava();
    }

    public IndexResult replicateIndex(IndexOperation op) throws IOException {
        return replicateIndex(op, WaitForActiveShards.DEFAULT);
    }

    public IndexResult replicateIndex(IndexOperation op, WaitForActiveShards waitForActiveShards) throws IOException {
        return new ReplicationOperation(this, waitForActiveShards).executeIndex(op);
    }

    public DeleteResult replicateDelete(DeleteOperation op) throws IOException {
        return replicateDelete(op, WaitForActiveShards.DEFAULT);
    }

    public DeleteResult replicateDelete(DeleteOperation op, WaitForActiveShards waitForActiveShards) throws IOException {
        return new ReplicationOperation(this, waitForActiveShards).executeDelete(op);
    }

    public NoOpResult replicateNoOp(NoOpOperation op) throws IOException {
        return replicateNoOp(op, WaitForActiveShards.DEFAULT);
    }

    public NoOpResult replicateNoOp(NoOpOperation op, WaitForActiveShards waitForActiveShards) throws IOException {
        return new ReplicationOperation(this, waitForActiveShards).executeNoOp(op);
    }

    public PrimaryContext promoteReplicaToPrimary(String allocationId) throws IOException {
        ShardCopy candidate;
        synchronized (mutex) {
            candidate = inSyncReplicas.get(allocationId);
            if (candidate == null) {
                throw new IllegalStateException("cannot promote allocation id [" + allocationId
                    + "]: not currently an in-sync replica of shard " + shardId);
            }
        }
        long newTerm = primary.primaryContext().currentTerm() + 1;
        candidate.primaryContext().assertNotStale(newTerm);
        ShardCopy oldPrimary = primary;
        candidate.setRole(ShardCopy.Role.PRIMARY);
        oldPrimary.setRole(ShardCopy.Role.IN_SYNC_REPLICA);
        synchronized (mutex) {
            inSyncReplicas.remove(allocationId);
        }
        primary = candidate;
        invalidateAllConnections();
        checkpointTracker.markAllocationIdAsInSync(candidate.allocationId());
        checkpointTracker.removeAllocationId(oldPrimary.allocationId());
        resyncAfterPromotion(newTerm);
        return candidate.primaryContext();
    }

    private void resyncAfterPromotion(long newTerm) throws IOException {
        long fromSeqNo = checkpointTracker.getGlobalCheckpoint() + 1;
        List<Operation> ops = TranslogResync.opsFrom(primary.indexShard(), fromSeqNo);
        for (ShardCopy replica : inSyncReplicasSnapshot()) {
            for (Operation op : ops) {
                resyncOne(replica, op, newTerm);
            }
        }
    }

    /**
     * Replays every operation the primary holds from the current global checkpoint onward to a
     * single in-sync replica. Used to bring a remaining replica fully up to date whenever it is
     * (re)attached to a replication group whose primary just changed, so a promotion resyncs every
     * surviving in-sync replica and not just the one candidate that was promoted.
     */
    public void resyncReplica(ShardCopy replica) throws IOException {
        long fromSeqNo = checkpointTracker.getGlobalCheckpoint() + 1;
        long term = primary.primaryContext().currentTerm();
        List<Operation> ops = TranslogResync.opsFrom(primary.indexShard(), fromSeqNo);
        for (Operation op : ops) {
            resyncOne(replica, op, term);
        }
    }

    private void resyncOne(ShardCopy replica, Operation op, long newTerm) {
        ReplicationRequest request = toRequest(op, newTerm);
        Connection connection = connectionTo(replica);
        CompletableFuture<ReplicationResponse> future = new CompletableFuture<>();
        primary.transportService().sendRequest(connection, REPLICATE_ACTION, request,
            TransportRequestOptions.of().withTimeout(replicaTimeoutMillis),
            new TransportResponseHandler<ReplicationResponse>() {
                @Override
                public void handleResponse(ReplicationResponse response) {
                    future.complete(response);
                }

                @Override
                public void handleException(TransportException exp) {
                    future.completeExceptionally(exp);
                }

                @Override
                public com.naqqa.elasticsearch.common.io.stream.Writeable.Reader<ReplicationResponse> reader() {
                    return ReplicationResponse::new;
                }
            });
        try {
            ReplicationResponse response = future.get(replicaTimeoutMillis * 2L, TimeUnit.MILLISECONDS);
            checkpointTracker.updateLocalCheckpoint(replica.allocationId(), response.localCheckpoint());
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
            Exception failure = cause instanceof Exception ex ? ex : new RuntimeException(cause);
            markReplicaFailed(replica.allocationId());
            failureListener.onReplicaFailure(shardId, replica.allocationId(), failure);
        }
    }

    private ReplicationRequest toRequest(Operation op, long newTerm) {
        switch (op.opType()) {
            case INDEX: {
                Operation.Index idx = (Operation.Index) op;
                return ReplicationRequest.indexAtSeqNo(shardId, newTerm, idx.id(), idx.routing(), idx.source().toBytesArray(),
                    idx.seqNo(), idx.primaryTerm(), idx.version());
            }
            case DELETE: {
                Operation.Delete del = (Operation.Delete) op;
                return ReplicationRequest.deleteAtSeqNo(shardId, newTerm, del.id(), del.seqNo(), del.primaryTerm(), del.version());
            }
            case NO_OP: {
                Operation.NoOp noOp = (Operation.NoOp) op;
                return ReplicationRequest.noOpAtSeqNo(shardId, newTerm, noOp.reason(), noOp.seqNo(), noOp.primaryTerm());
            }
            default:
                throw new IllegalStateException("unhandled translog op type [" + op.opType() + "]");
        }
    }
}
