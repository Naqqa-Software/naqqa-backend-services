package com.naqqa.elasticsearch.index.replication;

import com.naqqa.elasticsearch.common.json.JsonValue;
import com.naqqa.elasticsearch.common.json.JsonWriter;
import com.naqqa.elasticsearch.index.engine.DeleteOperation;
import com.naqqa.elasticsearch.index.engine.DeleteResult;
import com.naqqa.elasticsearch.index.engine.IndexOperation;
import com.naqqa.elasticsearch.index.engine.IndexResult;
import com.naqqa.elasticsearch.index.engine.NoOpOperation;
import com.naqqa.elasticsearch.index.engine.NoOpResult;
import com.naqqa.elasticsearch.transport.Connection;
import com.naqqa.elasticsearch.transport.RemoteTransportException;
import com.naqqa.elasticsearch.transport.TransportException;
import com.naqqa.elasticsearch.transport.TransportRequestOptions;
import com.naqqa.elasticsearch.transport.TransportResponseHandler;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

public final class ReplicationOperation {

    private final ReplicationGroup group;
    private final WaitForActiveShards waitForActiveShards;

    public ReplicationOperation(ReplicationGroup group, WaitForActiveShards waitForActiveShards) {
        this.group = group;
        this.waitForActiveShards = waitForActiveShards;
    }

    public IndexResult executeIndex(IndexOperation op) throws IOException {
        return executeIndex(op, true);
    }

    public IndexResult executeIndex(IndexOperation op, boolean fsyncTranslog) throws IOException {
        ShardCopy primary = ensurePrimaryUsable();
        IndexResult result = primary.indexShard().index(op, fsyncTranslog);
        if (!result.success()) {
            return result;
        }
        group.recordPrimaryCheckpoint(primary.indexShard().localCheckpoint());
        byte[] sourceBytes = JsonWriter.toJsonBytes(JsonValue.wrap(op.source()), false);
        ReplicationRequest request = ReplicationRequest.indexAtSeqNo(group.shardId(), primary.primaryContext().currentTerm(),
            op.id(), op.routing(), sourceBytes, result.seqNo(), result.primaryTerm(), result.version());
        replicateToReplicas(primary, request);
        return result;
    }

    public DeleteResult executeDelete(DeleteOperation op) throws IOException {
        return executeDelete(op, true);
    }

    public DeleteResult executeDelete(DeleteOperation op, boolean fsyncTranslog) throws IOException {
        ShardCopy primary = ensurePrimaryUsable();
        DeleteResult result = primary.indexShard().delete(op, fsyncTranslog);
        if (!result.success()) {
            return result;
        }
        group.recordPrimaryCheckpoint(primary.indexShard().localCheckpoint());
        ReplicationRequest request = ReplicationRequest.deleteAtSeqNo(group.shardId(), primary.primaryContext().currentTerm(), op.id(),
            result.seqNo(), result.primaryTerm(), result.version());
        replicateToReplicas(primary, request);
        return result;
    }

    public NoOpResult executeNoOp(NoOpOperation op) throws IOException {
        ShardCopy primary = ensurePrimaryUsable();
        NoOpResult result = primary.indexShard().noOp(op.reason());
        if (!result.success()) {
            return result;
        }
        group.recordPrimaryCheckpoint(primary.indexShard().localCheckpoint());
        ReplicationRequest request = ReplicationRequest.noOpAtSeqNo(group.shardId(), primary.primaryContext().currentTerm(), op.reason(),
            result.seqNo(), result.primaryTerm());
        replicateToReplicas(primary, request);
        return result;
    }

    private ShardCopy ensurePrimaryUsable() {
        ShardCopy primary = group.primary();
        if (primary.primaryContext().isSteppedDown()) {
            throw new PrimarySteppedDownException(group.shardId().toString());
        }
        return primary;
    }

    private void replicateToReplicas(ShardCopy primary, ReplicationRequest request) throws IOException {
        List<ShardCopy> replicas = group.inSyncReplicasSnapshot();
        int total = replicas.size();
        int required = waitForActiveShards.requiredReplicaAcks(total);
        if (total == 0 || required == 0) {
            return;
        }
        CompletableFuture<Void> done = new CompletableFuture<>();
        AtomicInteger acked = new AtomicInteger(0);
        AtomicInteger responded = new AtomicInteger(0);
        for (ShardCopy replica : replicas) {
            dispatchToReplica(primary, replica, request, acked, responded, total, required, done);
        }
        try {
            done.get(group.replicaTimeoutMillis() * 2L, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof StalePrimaryException stale) {
                throw stale;
            }
            if (cause instanceof NotEnoughReplicasException notEnough) {
                throw notEnough;
            }
            throw new IOException("replication failed for shard " + group.shardId(), cause);
        } catch (TimeoutException | InterruptedException e) {
            throw new IOException("timed out waiting for replica acks for shard " + group.shardId(), e);
        }
    }

    private void dispatchToReplica(ShardCopy primary, ShardCopy replica, ReplicationRequest request, AtomicInteger acked,
                                    AtomicInteger responded, int total, int required, CompletableFuture<Void> done) {
        TransportResponseHandler<ReplicationResponse> handler = new TransportResponseHandler<ReplicationResponse>() {
            @Override
            public void handleResponse(ReplicationResponse response) {
                group.recordReplicaCheckpoint(replica.allocationId(), response.localCheckpoint());
                onReplicaResponded(true, acked, responded, total, required, done);
            }

            @Override
            public void handleException(TransportException exp) {
                if (isStalePrimary(exp)) {
                    primary.primaryContext().stepDown();
                    done.completeExceptionally(unwrapStalePrimary(exp));
                    return;
                }
                group.markReplicaFailed(replica.allocationId());
                group.failureListener().onReplicaFailure(group.shardId(), replica.allocationId(), exp);
                onReplicaResponded(false, acked, responded, total, required, done);
            }

            @Override
            public com.naqqa.elasticsearch.common.io.stream.Writeable.Reader<ReplicationResponse> reader() {
                return ReplicationResponse::new;
            }
        };
        try {
            Connection connection = group.connectionTo(replica);
            primary.transportService().sendRequest(connection, ReplicationGroup.REPLICATE_ACTION, request,
                TransportRequestOptions.of().withTimeout(group.replicaTimeoutMillis()), handler);
        } catch (Exception e) {
            group.markReplicaFailed(replica.allocationId());
            group.failureListener().onReplicaFailure(group.shardId(), replica.allocationId(), e);
            onReplicaResponded(false, acked, responded, total, required, done);
        }
    }

    private void onReplicaResponded(boolean success, AtomicInteger acked, AtomicInteger responded,
                                      int total, int required, CompletableFuture<Void> done) {
        int a = success ? acked.incrementAndGet() : acked.get();
        int r = responded.incrementAndGet();
        if (a >= required) {
            done.complete(null);
        } else if (r == total) {
            done.completeExceptionally(new NotEnoughReplicasException(group.shardId().toString(), required, a));
        }
    }

    private static boolean isStalePrimary(TransportException exp) {
        return exp instanceof RemoteTransportException rte
            && StalePrimaryException.class.getName().equals(rte.remoteExceptionClassName());
    }

    private static StalePrimaryException unwrapStalePrimary(TransportException exp) {
        StalePrimaryException stale = new StalePrimaryException(-1L, -1L);
        stale.initCause(exp);
        return stale;
    }
}
