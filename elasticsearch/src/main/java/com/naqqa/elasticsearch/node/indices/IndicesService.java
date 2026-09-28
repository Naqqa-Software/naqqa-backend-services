package com.naqqa.elasticsearch.node.indices;

import com.naqqa.elasticsearch.analysis.registry.AnalysisRegistry;
import com.naqqa.elasticsearch.cluster.node.DiscoveryNode;
import com.naqqa.elasticsearch.cluster.routing.IndexRoutingTable;
import com.naqqa.elasticsearch.cluster.routing.IndexShardRoutingTable;
import com.naqqa.elasticsearch.cluster.routing.ShardId;
import com.naqqa.elasticsearch.cluster.routing.ShardRouting;
import com.naqqa.elasticsearch.cluster.routing.allocation.FailedShardEntry;
import com.naqqa.elasticsearch.cluster.service.ClusterChangedEvent;
import com.naqqa.elasticsearch.cluster.service.ClusterStateApplier;
import com.naqqa.elasticsearch.cluster.state.ClusterState;
import com.naqqa.elasticsearch.cluster.state.IndexMetadata;
import com.naqqa.elasticsearch.cluster.state.Metadata;
import com.naqqa.elasticsearch.common.json.JsonValue;
import com.naqqa.elasticsearch.common.threadpool.ThreadPool;
import com.naqqa.elasticsearch.index.engine.DeleteOperation;
import com.naqqa.elasticsearch.index.engine.DeleteResult;
import com.naqqa.elasticsearch.index.engine.IndexOperation;
import com.naqqa.elasticsearch.index.engine.IndexResult;
import com.naqqa.elasticsearch.index.engine.NoOpResult;
import com.naqqa.elasticsearch.index.replication.PrimaryContext;
import com.naqqa.elasticsearch.index.replication.ReplicationGroup;
import com.naqqa.elasticsearch.index.replication.ReplicationRequest;
import com.naqqa.elasticsearch.index.replication.ReplicationResponse;
import com.naqqa.elasticsearch.index.replication.ShardCopy;
import com.naqqa.elasticsearch.index.seqno.SequenceNumbers;
import com.naqqa.elasticsearch.index.shard.IndexShard;
import com.naqqa.elasticsearch.index.translog.VersionType;
import com.naqqa.elasticsearch.node.cluster.ClusterStateManager;
import com.naqqa.elasticsearch.node.cluster.NodeConnections;
import com.naqqa.elasticsearch.node.cluster.Wire;
import com.naqqa.elasticsearch.transport.TransportChannel;
import com.naqqa.elasticsearch.transport.TransportService;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

public final class IndicesService implements ClusterStateApplier, Closeable {

    public static final String BROADCAST_REFRESH_ACTION = "internal:admin/shard/refresh";

    static final class ReplicaShard {
        final ShardId shardId;
        final String allocationId;
        final PrimaryContext context;
        final Object lock = new Object();
        final List<ReplicationRequest> buffer = new ArrayList<>();
        volatile IndexShard shard;
        volatile boolean recovering = true;
        volatile boolean cancelled;

        ReplicaShard(ShardId shardId, String allocationId, long primaryTerm) {
            this.shardId = shardId;
            this.allocationId = allocationId;
            this.context = new PrimaryContext(primaryTerm);
        }
    }

    private final Path indicesPath;
    private final String localNodeId;
    private final TransportService transportService;
    private final AnalysisRegistry analysisRegistry;
    private final ClusterStateManager clusterStateManager;
    private final NodeConnections connections;
    private final TransportService sinkTransport;
    private final PeerRecoveryService recoveryService;
    private final ExecutorService recoveryExecutor;
    private final Map<String, IndexService> indices = new ConcurrentHashMap<>();
    private final Map<ShardId, ReplicationGroup> replicationGroups = new ConcurrentHashMap<>();
    private final Map<ShardId, ReplicaShard> replicas = new ConcurrentHashMap<>();
    private final Set<String> startedRequested = ConcurrentHashMap.newKeySet();
    private final Set<String> failedAllocations = ConcurrentHashMap.newKeySet();
    private final Map<ShardId, String> failures = new ConcurrentHashMap<>();
    private volatile ClusterState lastApplied;

    public IndicesService(Path indicesPath, String localNodeId, TransportService transportService,
                          AnalysisRegistry analysisRegistry, ClusterStateManager clusterStateManager) throws IOException {
        this(indicesPath, localNodeId, transportService, analysisRegistry, clusterStateManager, null, null);
    }

    public IndicesService(Path indicesPath, String localNodeId, TransportService transportService,
                          AnalysisRegistry analysisRegistry, ClusterStateManager clusterStateManager,
                          NodeConnections connections, ThreadPool threadPool) throws IOException {
        this.indicesPath = indicesPath;
        this.localNodeId = localNodeId;
        this.transportService = transportService;
        this.analysisRegistry = analysisRegistry;
        this.clusterStateManager = clusterStateManager;
        this.connections = connections;
        Files.createDirectories(indicesPath);
        if (connections != null && threadPool != null) {
            this.sinkTransport = new TransportService(localNodeId + "-remote-copies", new InetSocketAddress("127.0.0.1", 0), threadPool);
            this.recoveryService = new PeerRecoveryService(transportService, threadPool, localNodeId, this::prepareRecoverySource);
            this.recoveryExecutor = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "peer-recovery[" + localNodeId + "]");
                t.setDaemon(true);
                return t;
            });
            transportService.registerRequestHandler(ReplicationGroup.REPLICATE_ACTION, ReplicationRequest::new, this::handleReplicaOperation);
            Wire.register(transportService, BROADCAST_REFRESH_ACTION, this::handleBroadcastRefresh);
        } else {
            this.sinkTransport = null;
            this.recoveryService = null;
            this.recoveryExecutor = null;
        }
    }

    private CompletableFuture<byte[]> handleBroadcastRefresh(byte[] payload) {
        Map<String, Object> request = Wire.decode(payload);
        ShardId shardId = new ShardId(String.valueOf(request.get("index")), ((Number) request.get("shard")).intValue());
        IndexShard shard = shard(shardId);
        boolean ok = false;
        if (shard != null) {
            try {
                shard.refresh();
                ok = true;
            } catch (IOException | RuntimeException e) {
                System.err.println("[indices] broadcast refresh of " + shardId + " failed: " + e.getMessage());
            }
        }
        return CompletableFuture.completedFuture(Wire.encode(Map.of("ok", ok)));
    }

    /**
     * Refreshes every copy (primary and replicas) of every shard of the given indices across the
     * whole cluster, not just the copies allocated on this node.
     */
    public void refreshEverywhere(List<String> indices) {
        ClusterState state = clusterStateManager.state();
        for (String index : indices) {
            IndexRoutingTable irt = state.getRoutingTable().index(index);
            if (irt == null) {
                continue;
            }
            for (IndexShardRoutingTable table : irt.getShards().values()) {
                for (ShardRouting sr : table.getShards()) {
                    if (sr.currentNodeId() == null || !(sr.active() || sr.initializing())) {
                        continue;
                    }
                    ShardId shardId = new ShardId(index, sr.getShardId());
                    if (localNodeId.equals(sr.currentNodeId())) {
                        IndexShard shard = shard(shardId);
                        if (shard != null) {
                            try {
                                shard.refresh();
                            } catch (IOException | RuntimeException e) {
                                System.err.println("[indices] refresh of " + shardId + " failed: " + e.getMessage());
                            }
                        }
                    } else if (connections != null) {
                        DiscoveryNode node = state.getNodes().get(sr.currentNodeId());
                        if (node == null) {
                            continue;
                        }
                        try {
                            Wire.sendSync(transportService, connections.get(node), BROADCAST_REFRESH_ACTION,
                                Wire.encode(Map.of("index", index, "shard", sr.getShardId())), 10_000L);
                        } catch (Exception e) {
                            System.err.println("[indices] broadcast refresh to [" + node.getName() + "] for "
                                + index + "[" + sr.getShardId() + "] failed: " + e.getMessage());
                        }
                    }
                }
            }
        }
    }

    public Map<ShardId, ReplicationGroup> replicationGroups() {
        return replicationGroups;
    }

    public Map<String, IndexService> indices() {
        return indices;
    }

    public IndexService indexService(String index) {
        return indices.get(index);
    }

    public IndexShard shard(ShardId shardId) {
        IndexService service = indices.get(shardId.index());
        return service == null ? null : service.shard(shardId.id());
    }

    public List<IndexShard> localShards(String index) {
        IndexService service = indices.get(index);
        return service == null ? List.of() : service.shardsInOrder();
    }

    public Map<ShardId, String> shardFailures() {
        return failures;
    }

    public boolean isLocalPrimary(ShardId shardId) {
        return replicationGroups.containsKey(shardId);
    }

    public boolean isStartedReplica(ShardId shardId) {
        ReplicaShard rs = replicas.get(shardId);
        return rs != null && !rs.recovering && !rs.cancelled && rs.shard != null;
    }

    public Path indexPath(IndexMetadata imd) {
        return indicesPath.resolve(imd.getIndexUUID());
    }

    private boolean multiNode() {
        return connections != null && clusterStateManager.isMultiNode();
    }

    @Override
    public synchronized void applyClusterState(ClusterChangedEvent event) {
        ClusterState state = event.state();
        lastApplied = state;
        Metadata metadata = state.getMetadata();
        for (String existing : new ArrayList<>(indices.keySet())) {
            IndexService service = indices.get(existing);
            IndexMetadata imd = metadata.index(existing);
            if (imd == null || !imd.getIndexUUID().equals(service.uuid())) {
                removeIndex(existing, true);
            } else if (imd.getState() == IndexMetadata.State.CLOSE) {
                removeIndex(existing, false);
            }
        }
        for (IndexMetadata imd : metadata.getIndices().values()) {
            if (imd.getState() == IndexMetadata.State.CLOSE) {
                continue;
            }
            IndexRoutingTable routing = state.getRoutingTable().index(imd.getIndex());
            if (routing == null) {
                continue;
            }
            List<ShardRouting> localCopies = new ArrayList<>();
            for (IndexShardRoutingTable table : routing.getShards().values()) {
                for (ShardRouting sr : table.getShards()) {
                    if (localNodeId.equals(sr.currentNodeId()) && (sr.initializing() || sr.active())
                        && (sr.primary() || multiNode())) {
                        localCopies.add(sr);
                    }
                }
            }
            IndexService service = indices.get(imd.getIndex());
            if (service == null && (!localCopies.isEmpty() || multiNode())) {
                try {
                    service = new IndexService(imd, indexPath(imd), analysisRegistry);
                    indices.put(imd.getIndex(), service);
                } catch (RuntimeException e) {
                    System.err.println("[indices] failed to create index service [" + imd.getIndex() + "]: " + e);
                    continue;
                }
            }
            if (service == null) {
                continue;
            }
            service.updateMetadata(imd);
            Set<Integer> wanted = new HashSet<>();
            List<ShardRouting> toStart = new ArrayList<>();
            for (ShardRouting sr : localCopies) {
                wanted.add(sr.getShardId());
                ShardId shardId = new ShardId(imd.getIndex(), sr.getShardId());
                IndexShardRoutingTable table = routing.shard(sr.getShardId());
                try {
                    if (sr.primary()) {
                        applyPrimary(service, imd, state, table, sr, toStart);
                    } else {
                        applyReplica(service, imd, state, table, sr);
                    }
                } catch (IOException | RuntimeException e) {
                    failures.put(shardId, String.valueOf(e));
                    System.err.println("[indices] failed to apply shard " + shardId + ": " + e);
                }
            }
            for (Integer shardId : new ArrayList<>(service.shards().keySet())) {
                if (!wanted.contains(shardId)) {
                    ShardId sid = new ShardId(imd.getIndex(), shardId);
                    replicationGroups.remove(sid);
                    cancelReplica(sid);
                    service.closeShard(shardId);
                }
            }
            for (ShardId sid : new ArrayList<>(replicas.keySet())) {
                if (sid.index().equals(imd.getIndex()) && !wanted.contains(sid.id())) {
                    cancelReplica(sid);
                }
            }
            for (ShardId sid : new ArrayList<>(replicationGroups.keySet())) {
                if (sid.index().equals(imd.getIndex()) && !wanted.contains(sid.id())) {
                    replicationGroups.remove(sid);
                }
            }
            if (!toStart.isEmpty()) {
                markShardsStarted(toStart);
            }
        }
    }

    private com.naqqa.elasticsearch.transport.DiscoveryNode localTransportNode() {
        return transportService.localNode();
    }

    private void applyPrimary(IndexService service, IndexMetadata imd, ClusterState state, IndexShardRoutingTable table,
                              ShardRouting sr, List<ShardRouting> toStart) throws IOException {
        ShardId shardId = new ShardId(imd.getIndex(), sr.getShardId());
        String allocationId = sr.allocationId() == null ? localNodeId + "-" + shardId : sr.allocationId().getId();
        long term = imd.primaryTerm(sr.getShardId());
        ReplicationGroup group = replicationGroups.get(shardId);
        if (group != null && group.primary().allocationId().equals(allocationId)) {
            advanceTerm(group.primary().primaryContext(), term);
        } else {
            ReplicaShard asReplica = replicas.get(shardId);
            if (asReplica != null && asReplica.allocationId.equals(allocationId) && asReplica.shard != null && !asReplica.recovering) {
                group = promote(shardId, asReplica, term);
                replicas.remove(shardId, asReplica);
            } else {
                if (asReplica != null) {
                    cancelReplica(shardId);
                }
                IndexShard shard = service.openShard(sr.getShardId());
                ShardCopy copy = new ShardCopy(allocationId, localTransportNode(), shard, transportService, term, ShardCopy.Role.PRIMARY);
                group = new ReplicationGroup(shardId, copy, this::onReplicaFailure);
            }
            replicationGroups.put(shardId, group);
        }
        failures.remove(shardId);
        if (multiNode()) {
            syncReplicas(shardId, group, imd, state, table);
        }
        if (sr.initializing() && startedRequested.add(allocationId)) {
            toStart.add(sr);
        }
    }

    private static void advanceTerm(PrimaryContext context, long term) {
        try {
            context.assertNotStale(term);
        } catch (RuntimeException ignored) {
        }
    }

    private ReplicationGroup promote(ShardId shardId, ReplicaShard replica, long metadataTerm) {
        long previousTerm = replica.context.currentTerm();
        ShardCopy formerPrimary = new ShardCopy("former-primary-" + shardId.index() + "-" + shardId.id(), localTransportNode(), null,
            sinkTransport, previousTerm, ShardCopy.Role.PRIMARY);
        ShardCopy candidate = new ShardCopy(replica.allocationId, localTransportNode(), replica.shard, sinkTransport, previousTerm,
            ShardCopy.Role.IN_SYNC_REPLICA);
        ReplicationGroup handover = new ReplicationGroup(shardId, formerPrimary, (sid, allocId, cause) -> {
        });
        handover.addInSyncReplica(candidate);
        long promotedTerm = previousTerm + 1;
        try {
            promotedTerm = handover.promoteReplicaToPrimary(replica.allocationId).currentTerm();
        } catch (IOException | RuntimeException e) {
            System.err.println("[indices] promotion handover for " + shardId + " reported: " + e.getMessage());
        }
        long term = Math.max(metadataTerm, promotedTerm);
        ShardCopy primary = new ShardCopy(replica.allocationId, localTransportNode(), replica.shard, transportService, term,
            ShardCopy.Role.PRIMARY);
        System.err.println("[indices] promoted replica " + replica.allocationId + " of " + shardId + " to primary with term [" + term + "]");
        return new ReplicationGroup(shardId, primary, this::onReplicaFailure);
    }

    private void syncReplicas(ShardId shardId, ReplicationGroup group, IndexMetadata imd, ClusterState state,
                              IndexShardRoutingTable table) {
        Map<String, ShardRouting> expected = new LinkedHashMap<>();
        for (ShardRouting copy : table.getShards()) {
            if (!copy.primary() && copy.allocationId() != null && copy.currentNodeId() != null && (copy.initializing() || copy.active())
                && state.getNodes().nodeExists(copy.currentNodeId())) {
                expected.put(copy.allocationId().getId(), copy);
            }
        }
        List<ShardCopy> current = new ArrayList<>(group.inSyncReplicasSnapshot());
        current.addAll(group.initializingReplicasSnapshot());
        boolean stale = false;
        for (ShardCopy copy : current) {
            if (!expected.containsKey(copy.allocationId()) || failedAllocations.contains(copy.allocationId())) {
                stale = true;
                break;
            }
        }
        if (!group.failedReplicasSnapshot().isEmpty()) {
            stale = true;
        }
        ReplicationGroup target = group;
        if (stale) {
            target = new ReplicationGroup(shardId, group.primary(), this::onReplicaFailure);
            for (ShardCopy copy : group.inSyncReplicasSnapshot()) {
                if (expected.containsKey(copy.allocationId()) && !failedAllocations.contains(copy.allocationId())) {
                    target.addInSyncReplica(copy);
                }
            }
        }
        Set<String> inSync = imd.inSyncAllocationIds(shardId.id());
        Set<String> present = new HashSet<>();
        for (ShardCopy copy : target.inSyncReplicasSnapshot()) {
            present.add(copy.allocationId());
        }
        for (ShardRouting copy : expected.values()) {
            String id = copy.allocationId().getId();
            if (copy.active() && inSync.contains(id) && !present.contains(id) && !failedAllocations.contains(id)) {
                ShardCopy newCopy = remoteCopy(state, copy, target.primary().primaryContext().currentTerm());
                target.addInSyncReplica(newCopy);
                // Whenever a replica is (re)attached to a group whose primary just changed (e.g. after
                // a promotion) it must be caught up from the current global checkpoint, not just the
                // one candidate that was promoted, otherwise the other in-sync copies silently drift.
                try {
                    target.resyncReplica(newCopy);
                } catch (IOException e) {
                    System.err.println("[indices] failed to resync replica [" + id + "] of " + shardId + ": " + e.getMessage());
                }
            }
        }
        if (target != group) {
            replicationGroups.put(shardId, target);
        }
    }

    private ShardCopy remoteCopy(ClusterState state, ShardRouting copy, long term) {
        DiscoveryNode node = state.getNodes().get(copy.currentNodeId());
        return new ShardCopy(copy.allocationId().getId(), NodeConnections.toTransportNode(node), null, sinkTransport, term,
            ShardCopy.Role.IN_SYNC_REPLICA);
    }

    private synchronized PeerRecoveryService.Source prepareRecoverySource(ShardId shardId, String targetAllocationId, String targetNodeId)
        throws IOException {
        ReplicationGroup group = replicationGroups.get(shardId);
        ClusterState state = lastApplied;
        if (group == null || state == null) {
            throw new IllegalStateException("node [" + localNodeId + "] does not hold the primary of " + shardId);
        }
        DiscoveryNode target = state.getNodes().get(targetNodeId);
        if (target == null) {
            throw new IllegalStateException("recovery target node [" + targetNodeId + "] is not part of the cluster");
        }
        failedAllocations.remove(targetAllocationId);
        boolean tracked = false;
        for (ShardCopy copy : group.inSyncReplicasSnapshot()) {
            tracked |= copy.allocationId().equals(targetAllocationId);
        }
        if (!tracked) {
            group.addInSyncReplica(new ShardCopy(targetAllocationId, NodeConnections.toTransportNode(target), null, sinkTransport,
                group.primary().primaryContext().currentTerm(), ShardCopy.Role.IN_SYNC_REPLICA));
        }
        IndexShard shard = group.primary().indexShard();
        shard.flush(true);
        IndexService service = indices.get(shardId.index());
        if (service == null) {
            throw new IllegalStateException("index [" + shardId.index() + "] is not open on node [" + localNodeId + "]");
        }
        return new PeerRecoveryService.Source(shard, service.shardPath(shardId.id()), service.translogConfig(shardId.id()));
    }

    private void applyReplica(IndexService service, IndexMetadata imd, ClusterState state, IndexShardRoutingTable table,
                              ShardRouting sr) {
        ShardId shardId = new ShardId(imd.getIndex(), sr.getShardId());
        String allocationId = sr.allocationId().getId();
        long term = imd.primaryTerm(sr.getShardId());
        if (replicationGroups.remove(shardId) != null) {
            service.closeShard(sr.getShardId());
        }
        ReplicaShard existing = replicas.get(shardId);
        if (existing != null && !existing.allocationId.equals(allocationId)) {
            cancelReplica(shardId);
            service.closeShard(sr.getShardId());
            existing = null;
        }
        if (existing != null) {
            advanceTerm(existing.context, term);
            return;
        }
        ShardRouting primary = table.primaryShard();
        if (primary == null || !primary.active() || primary.currentNodeId() == null
            || !state.getNodes().nodeExists(primary.currentNodeId()) || recoveryExecutor == null) {
            return;
        }
        if (failedAllocations.contains(allocationId)) {
            return;
        }
        ReplicaShard replica = new ReplicaShard(shardId, allocationId, term);
        replicas.put(shardId, replica);
        DiscoveryNode primaryNode = state.getNodes().get(primary.currentNodeId());
        boolean alreadyStarted = sr.active();
        recoveryExecutor.execute(() -> recoverReplica(replica, service, sr, primaryNode, alreadyStarted));
    }

    private void recoverReplica(ReplicaShard replica, IndexService service, ShardRouting routing, DiscoveryNode primaryNode,
                                boolean alreadyStarted) {
        try {
            IndexShard shard = recoveryService.recover(connections.get(primaryNode), replica.shardId, replica.allocationId, service);
            synchronized (replica.lock) {
                if (replica.cancelled) {
                    return;
                }
                for (ReplicationRequest buffered : replica.buffer) {
                    try {
                        applyOnReplica(shard, buffered);
                    } catch (IOException | RuntimeException e) {
                        System.err.println("[indices] failed to replay buffered op on " + replica.shardId + ": " + e.getMessage());
                    }
                }
                replica.buffer.clear();
                replica.shard = shard;
                replica.recovering = false;
            }
            failures.remove(replica.shardId);
            if (!alreadyStarted && startedRequested.add(replica.allocationId)) {
                markShardsStarted(List.of(routing));
            }
        } catch (Exception e) {
            System.err.println("[indices] peer recovery of " + replica.shardId + " [" + replica.allocationId + "] from ["
                + primaryNode.getName() + "] failed: " + e);
            failures.put(replica.shardId, String.valueOf(e));
            replicas.remove(replica.shardId, replica);
            replica.cancelled = true;
            failShard(routing, "peer recovery failed: " + e.getMessage());
        }
    }

    private void cancelReplica(ShardId shardId) {
        ReplicaShard replica = replicas.remove(shardId);
        if (replica != null) {
            synchronized (replica.lock) {
                replica.cancelled = true;
                replica.buffer.clear();
            }
        }
    }

    private void handleReplicaOperation(ReplicationRequest request, TransportChannel channel) throws Exception {
        ShardId shardId = request.shardId();
        ReplicaShard replica = replicas.get(shardId);
        if (replica == null || replica.cancelled) {
            throw new IllegalStateException("shard " + shardId + " is not allocated as a replica on node [" + localNodeId + "]");
        }
        replica.context.assertNotStale(request.primaryTerm());
        synchronized (replica.lock) {
            if (replica.recovering) {
                replica.buffer.add(request);
                channel.sendResponse(new ReplicationResponse(request.seqNo(), request.opPrimaryTerm(), request.version(),
                    SequenceNumbers.NO_OPS_PERFORMED));
                return;
            }
        }
        channel.sendResponse(applyOnReplica(replica.shard, request));
    }

    @SuppressWarnings("unchecked")
    static ReplicationResponse applyOnReplica(IndexShard shard, ReplicationRequest request) throws IOException {
        if (!request.hasExplicitSeqNo()) {
            return applyWithoutSeqNo(shard, request);
        }
        long seqNo = request.seqNo();
        long term = request.opPrimaryTerm();
        if (shard.hasProcessedSeqNo(seqNo)) {
            return new ReplicationResponse(seqNo, term, request.version(), shard.localCheckpoint());
        }
        switch (request.opType()) {
            case INDEX -> {
                Map<String, Object> source = (Map<String, Object>) JsonValue.parse(request.source()).toJava();
                IndexOperation op = new IndexOperation(request.id(), request.routing(), source, request.version(),
                    VersionType.EXTERNAL_GTE, SequenceNumbers.UNASSIGNED_SEQ_NO, SequenceNumbers.UNASSIGNED_PRIMARY_TERM);
                IndexResult result = shard.indexAtSeqNo(op, seqNo, term);
                if (!result.success()) {
                    fillGap(shard, seqNo, term, result.failure());
                }
            }
            case DELETE -> {
                DeleteOperation op = new DeleteOperation(request.id(), request.version(), VersionType.EXTERNAL_GTE,
                    SequenceNumbers.UNASSIGNED_SEQ_NO, SequenceNumbers.UNASSIGNED_PRIMARY_TERM);
                DeleteResult result = shard.deleteAtSeqNo(op, seqNo, term);
                if (!result.success()) {
                    fillGap(shard, seqNo, term, result.failure());
                }
            }
            case NOOP -> {
                NoOpResult result = shard.noOpAtSeqNo(request.reason(), seqNo, term);
                if (!result.success() && !shard.hasProcessedSeqNo(seqNo)) {
                    throw new IOException("replica failed to apply no-op at seq_no [" + seqNo + "]", result.failure());
                }
            }
            default -> throw new IOException("unsupported replication op type [" + request.opType() + "]");
        }
        return new ReplicationResponse(seqNo, term, request.version(), shard.localCheckpoint());
    }

    private static void fillGap(IndexShard shard, long seqNo, long term, Exception cause) throws IOException {
        if (shard.hasProcessedSeqNo(seqNo)) {
            return;
        }
        NoOpResult noOp = shard.noOpAtSeqNo("superseded operation: " + (cause == null ? "" : cause.getMessage()), seqNo, term);
        if (!noOp.success() && !shard.hasProcessedSeqNo(seqNo)) {
            throw new IOException("replica failed to apply operation at seq_no [" + seqNo + "]", cause);
        }
    }

    @SuppressWarnings("unchecked")
    private static ReplicationResponse applyWithoutSeqNo(IndexShard shard, ReplicationRequest request) throws IOException {
        switch (request.opType()) {
            case INDEX -> {
                Map<String, Object> source = (Map<String, Object>) JsonValue.parse(request.source()).toJava();
                IndexResult result = shard.index(IndexOperation.of(request.id(), request.routing(), source));
                if (!result.success()) {
                    throw new IOException("replica apply failed", result.failure());
                }
                return new ReplicationResponse(result.seqNo(), result.primaryTerm(), result.version(), shard.localCheckpoint());
            }
            case DELETE -> {
                DeleteResult result = shard.delete(DeleteOperation.of(request.id()));
                if (!result.success()) {
                    throw new IOException("replica apply failed", result.failure());
                }
                return new ReplicationResponse(result.seqNo(), result.primaryTerm(), result.version(), shard.localCheckpoint());
            }
            case NOOP -> {
                NoOpResult result = shard.noOp(request.reason());
                if (!result.success()) {
                    throw new IOException("replica apply failed", result.failure());
                }
                return new ReplicationResponse(result.seqNo(), result.primaryTerm(), 0L, shard.localCheckpoint());
            }
            default -> throw new IOException("unsupported replication op type [" + request.opType() + "]");
        }
    }

    private void onReplicaFailure(ShardId shardId, String allocationId, Exception cause) {
        failures.put(shardId, String.valueOf(cause));
        if (!multiNode() || !failedAllocations.add(allocationId)) {
            return;
        }
        ClusterState state = clusterStateManager.state();
        IndexRoutingTable irt = state.getRoutingTable().index(shardId.index());
        IndexShardRoutingTable table = irt == null ? null : irt.shard(shardId.id());
        if (table == null) {
            return;
        }
        for (ShardRouting sr : table.getShards()) {
            if (sr.allocationId() != null && sr.allocationId().getId().equals(allocationId)) {
                failShard(sr, "replica failed on primary: " + (cause == null ? "" : cause.getMessage()));
            }
        }
    }

    private void failShard(ShardRouting routing, String message) {
        if (!multiNode() || routing.allocationId() == null) {
            return;
        }
        String allocationId = routing.allocationId().getId();
        clusterStateManager.submit("shard-failed [" + routing.shardId() + "][" + allocationId + "]", current -> {
            IndexRoutingTable irt = current.getRoutingTable().index(routing.getIndex());
            IndexShardRoutingTable table = irt == null ? null : irt.shard(routing.getShardId());
            if (table == null) {
                return current;
            }
            ShardRouting match = null;
            for (ShardRouting sr : table.getShards()) {
                if (sr.allocationId() != null && sr.allocationId().getId().equals(allocationId)) {
                    match = sr;
                }
            }
            if (match == null) {
                return current;
            }
            ClusterState withMeta = current;
            IndexMetadata imd = current.getMetadata().index(routing.getIndex());
            if (imd != null && imd.inSyncAllocationIds(routing.getShardId()).contains(allocationId) && !match.primary()) {
                Set<String> inSync = new LinkedHashSet<>(imd.inSyncAllocationIds(routing.getShardId()));
                inSync.remove(allocationId);
                withMeta = current.builder().metadata(current.getMetadata().toBuilder()
                    .put(imd.builder().putInSyncAllocationIds(routing.getShardId(), inSync).build()).build()).build();
            }
            return clusterStateManager.allocationService().applyFailedShards(withMeta, List.of(new FailedShardEntry(match, message)),
                System.currentTimeMillis());
        }).whenComplete((s, e) -> {
            if (e != null) {
                System.err.println("[indices] failed to report shard failure for " + routing.shardId() + ": " + e.getMessage());
            }
        });
    }

    private void markShardsStarted(List<ShardRouting> shards) {
        clusterStateManager.submit("shard-started", current -> {
            List<ShardRouting> stillInitializing = new ArrayList<>();
            Metadata.Builder mb = current.getMetadata().toBuilder();
            boolean metaChanged = false;
            for (ShardRouting sr : shards) {
                IndexRoutingTable irt = current.getRoutingTable().index(sr.getIndex());
                if (irt == null) {
                    continue;
                }
                IndexShardRoutingTable table = irt.shard(sr.getShardId());
                if (table == null) {
                    continue;
                }
                for (ShardRouting candidate : table.getShards()) {
                    if (candidate.initializing() && candidate.allocationId() != null && sr.allocationId() != null
                        && candidate.allocationId().getId().equals(sr.allocationId().getId())) {
                        stillInitializing.add(candidate);
                        IndexMetadata imd = mb.build().index(sr.getIndex());
                        if (imd != null) {
                            Set<String> inSync = new LinkedHashSet<>(imd.inSyncAllocationIds(sr.getShardId()));
                            inSync.add(candidate.allocationId().getId());
                            mb.put(imd.builder().putInSyncAllocationIds(sr.getShardId(), inSync).build());
                            metaChanged = true;
                        }
                    }
                }
            }
            if (stillInitializing.isEmpty()) {
                return current;
            }
            ClusterState withMeta = metaChanged ? current.builder().metadata(mb.build()).build() : current;
            return clusterStateManager.allocationService().applyStartedShards(withMeta, stillInitializing, System.currentTimeMillis());
        }).whenComplete((s, e) -> {
            if (e != null) {
                for (ShardRouting sr : shards) {
                    if (sr.allocationId() != null) {
                        startedRequested.remove(sr.allocationId().getId());
                    }
                }
                System.err.println("[indices] failed to report started shards " + shards + ": " + e.getMessage());
            }
        });
    }

    private void removeIndex(String index, boolean deleteData) {
        IndexService service = indices.remove(index);
        if (service == null) {
            return;
        }
        replicationGroups.keySet().removeIf(id -> id.index().equals(index));
        for (ShardId sid : new ArrayList<>(replicas.keySet())) {
            if (sid.index().equals(index)) {
                cancelReplica(sid);
            }
        }
        failures.keySet().removeIf(id -> id.index().equals(index));
        service.close();
        if (deleteData) {
            deleteRecursively(service.indexPath());
        }
    }

    static void deleteRecursively(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    public CompletableFuture<Void> awaitShardsStarted(String index, long timeoutMillis) {
        return CompletableFuture.runAsync(() -> {
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (System.currentTimeMillis() < deadline) {
                ClusterState state = clusterStateManager.state();
                IndexRoutingTable irt = state.getRoutingTable().index(index);
                IndexMetadata imd = state.getMetadata().index(index);
                if (imd == null) {
                    return;
                }
                if (irt != null) {
                    boolean allPrimariesStarted = true;
                    for (IndexShardRoutingTable table : irt.getShards().values()) {
                        ShardRouting primary = table.primaryShard();
                        if (primary == null || !primary.active()
                            || (localNodeId.equals(primary.currentNodeId()) && replicationGroups.get(table.getShardId()) == null)) {
                            allPrimariesStarted = false;
                            break;
                        }
                    }
                    if (allPrimariesStarted && !irt.getShards().isEmpty()) {
                        return;
                    }
                }
                try {
                    Thread.sleep(5L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
    }

    public Map<String, Object> shardSummary() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, IndexService> e : indices.entrySet()) {
            out.put(e.getKey(), e.getValue().shards().keySet());
        }
        return out;
    }

    @Override
    public synchronized void close() {
        if (recoveryExecutor != null) {
            recoveryExecutor.shutdownNow();
        }
        if (recoveryService != null) {
            recoveryService.close();
        }
        for (String index : new ArrayList<>(indices.keySet())) {
            removeIndex(index, false);
        }
    }
}
