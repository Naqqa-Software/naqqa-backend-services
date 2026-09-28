package com.naqqa.elasticsearch.node.cluster;

import com.naqqa.elasticsearch.cluster.discovery.ClusterTransport;
import com.naqqa.elasticsearch.cluster.discovery.FileBasedSeedHostsProvider;
import com.naqqa.elasticsearch.cluster.discovery.SeedHostsProvider;
import com.naqqa.elasticsearch.cluster.discovery.StaticSeedHostsProvider;
import com.naqqa.elasticsearch.cluster.io.StreamUtils;
import com.naqqa.elasticsearch.cluster.node.ClusterNode;
import com.naqqa.elasticsearch.cluster.node.DiscoveryNode;
import com.naqqa.elasticsearch.cluster.node.DiscoveryNodes;
import com.naqqa.elasticsearch.cluster.node.NodeIdentity;
import com.naqqa.elasticsearch.cluster.routing.IndexRoutingTable;
import com.naqqa.elasticsearch.cluster.routing.IndexShardRoutingTable;
import com.naqqa.elasticsearch.cluster.routing.RoutingTable;
import com.naqqa.elasticsearch.cluster.routing.ShardRouting;
import com.naqqa.elasticsearch.cluster.routing.UnassignedInfo;
import com.naqqa.elasticsearch.cluster.routing.allocation.AllocationDeciders;
import com.naqqa.elasticsearch.cluster.routing.allocation.AllocationService;
import com.naqqa.elasticsearch.cluster.routing.allocation.BalancedShardsAllocator;
import com.naqqa.elasticsearch.cluster.routing.allocation.DiskUsageProvider;
import com.naqqa.elasticsearch.cluster.routing.allocation.decider.EnableAllocationDecider;
import com.naqqa.elasticsearch.cluster.routing.allocation.decider.FilterAllocationDecider;
import com.naqqa.elasticsearch.cluster.routing.allocation.decider.MaxRetryAllocationDecider;
import com.naqqa.elasticsearch.cluster.routing.allocation.decider.SameShardAllocationDecider;
import com.naqqa.elasticsearch.cluster.service.ClusterApplierService;
import com.naqqa.elasticsearch.cluster.service.ClusterChangedEvent;
import com.naqqa.elasticsearch.cluster.service.ClusterStateApplier;
import com.naqqa.elasticsearch.cluster.service.ClusterStateListener;
import com.naqqa.elasticsearch.cluster.service.ClusterStateTaskExecutor;
import com.naqqa.elasticsearch.cluster.service.MasterService;
import com.naqqa.elasticsearch.cluster.service.Priority;
import com.naqqa.elasticsearch.cluster.state.ClusterBlocks;
import com.naqqa.elasticsearch.cluster.state.ClusterState;
import com.naqqa.elasticsearch.cluster.state.IndexMetadata;
import com.naqqa.elasticsearch.cluster.state.Metadata;
import com.naqqa.elasticsearch.common.logging.ESLogger;
import com.naqqa.elasticsearch.transport.Connection;
import com.naqqa.elasticsearch.transport.TransportService;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.UnaryOperator;

public final class ClusterStateManager implements AutoCloseable, ClusterStateListener {

    private static final ESLogger LOG = ESLogger.getLogger(ClusterStateManager.class);

    public static final String PROPOSE_ACTION = "internal:cluster/state/propose";

    public record DiscoveryConfig(List<String> seedHosts, Path seedHostsFile, List<String> initialMasterNodes,
                                  long peerFinderIntervalMillis, long checkIntervalMillis, long checkTimeoutMillis,
                                  int checkRetries, long forwardTimeoutMillis) {

        public static DiscoveryConfig singleNode(String nodeName) {
            return new DiscoveryConfig(List.of(), null, List.of(nodeName), 1000L, 1000L, 10_000L, 3, 30_000L);
        }

        public boolean multiNode() {
            return !seedHosts.isEmpty() || seedHostsFile != null;
        }
    }

    public static final class UpdateTask {
        final String source;
        final UnaryOperator<ClusterState> update;
        final CompletableFuture<ClusterState> future = new CompletableFuture<>();
        Exception failure;

        UpdateTask(String source, UnaryOperator<ClusterState> update) {
            this.source = source;
            this.update = update;
        }
    }

    public static final class StaleBaseException extends RuntimeException {
        private final long masterVersion;

        StaleBaseException(long masterVersion) {
            super("cluster state update was computed against a stale base; master is at version [" + masterVersion + "]");
            this.masterVersion = masterVersion;
        }

        public long masterVersion() {
            return masterVersion;
        }
    }

    private final String clusterName;
    private final DiscoveryNode localNode;
    private final DiscoveryConfig config;
    private final ClusterTransport transport;
    private final LoopbackClusterTransport loopback;
    private final TransportService transportService;
    private final NodeConnections connections;
    private final ClusterNode clusterNode;
    private final AllocationService allocationService;
    private final long tickIntervalMillis;
    private final Object tickLock = new Object();
    private final ClusterStateTaskExecutor<UpdateTask> executor = this::executeBatch;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService forwarder;
    private volatile boolean started;
    private volatile boolean closed;
    private volatile long lastReconcileAt;
    private volatile boolean reconcilePending;

    public ClusterStateManager(String clusterName, DiscoveryNode localNode, Path stateDir, long tickIntervalMillis) {
        this(clusterName, localNode, stateDir, tickIntervalMillis, DiscoveryConfig.singleNode(localNode.getName()), null, null);
    }

    public ClusterStateManager(String clusterName, DiscoveryNode localNode, Path stateDir, long tickIntervalMillis,
                               DiscoveryConfig config, TransportService transportService, NodeConnections connections) {
        this.clusterName = clusterName;
        this.localNode = localNode;
        this.tickIntervalMillis = tickIntervalMillis;
        this.config = config;
        this.transportService = transportService;
        this.connections = connections;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cluster-coordinator[" + localNode.getName() + "]");
            t.setDaemon(true);
            return t;
        });
        this.forwarder = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "cluster-forwarder[" + localNode.getName() + "]");
            t.setDaemon(true);
            return t;
        });
        AllocationDeciders deciders = new AllocationDeciders(List.of(new SameShardAllocationDecider(),
            new MaxRetryAllocationDecider(), new FilterAllocationDecider(), new EnableAllocationDecider(),
            new NodeAllocationDeciders.LiveDataNodeDecider(), new NodeAllocationDeciders.NoRelocationDecider(),
            new NodeAllocationDeciders.ExistingPrimaryDecider()));
        this.allocationService = new AllocationService(deciders, new BalancedShardsAllocator(deciders), DiskUsageProvider.NONE);
        List<SeedHostsProvider> seeds = new ArrayList<>();
        List<String> initialMasters;
        if (config.multiNode() && transportService != null && connections != null) {
            this.loopback = null;
            this.transport = new TransportClusterTransport(transportService, localNode, clusterName, this::runOnCoordinator,
                connections, Math.max(1_000L, config.checkTimeoutMillis()));
            seeds.add(new StaticSeedHostsProvider(config.seedHosts()));
            if (config.seedHostsFile() != null) {
                seeds.add(new SafeFileSeedHostsProvider(config.seedHostsFile()));
            }
            initialMasters = config.initialMasterNodes();
            Wire.register(transportService, PROPOSE_ACTION, this::handleProposal);
        } else {
            this.loopback = new LoopbackClusterTransport(localNode);
            this.transport = loopback;
            seeds.add(new StaticSeedHostsProvider(List.of(localNode.getAddress())));
            initialMasters = config.initialMasterNodes().isEmpty() ? List.of(localNode.getName()) : config.initialMasterNodes();
        }
        this.clusterNode = new ClusterNode(clusterName, localNode, transport, stateDir, seeds, initialMasters,
            config.peerFinderIntervalMillis(), config.checkIntervalMillis(), config.checkTimeoutMillis(), config.checkRetries(),
            50L, electionRoundTimeout(localNode, loopback == null), allocationService);
        clusterNode.getApplierService().addListener(this);
    }

    private static long electionRoundTimeout(DiscoveryNode localNode, boolean multiNode) {
        if (!multiNode) {
            return 5_000L;
        }
        return 800L + Math.floorMod((localNode.getId() + localNode.getName()).hashCode(), 1_600);
    }

    private static final class SafeFileSeedHostsProvider implements SeedHostsProvider {
        private final FileBasedSeedHostsProvider delegate;

        SafeFileSeedHostsProvider(Path file) {
            this.delegate = new FileBasedSeedHostsProvider(file);
        }

        @Override
        public List<String> getSeedAddresses() {
            try {
                return delegate.getSeedAddresses();
            } catch (RuntimeException e) {
                return List.of();
            }
        }
    }

    private void runOnCoordinator(Runnable runnable) {
        scheduler.execute(() -> {
            synchronized (tickLock) {
                try {
                    runnable.run();
                } catch (Throwable t) {
                    LOG.warn("[cluster] coordinator task failed: " + t);
                }
            }
        });
    }

    public String clusterName() {
        return clusterName;
    }

    public DiscoveryNode localNode() {
        return localNode;
    }

    public boolean isMultiNode() {
        return loopback == null;
    }

    public ClusterNode clusterNode() {
        return clusterNode;
    }

    public AllocationService allocationService() {
        return allocationService;
    }

    public MasterService masterService() {
        return clusterNode.getMasterService();
    }

    public ClusterApplierService applierService() {
        return clusterNode.getApplierService();
    }

    public void addApplier(ClusterStateApplier applier) {
        clusterNode.getApplierService().addApplier(applier);
    }

    public void addListener(ClusterStateListener listener) {
        clusterNode.getApplierService().addListener(listener);
    }

    public void addPreCommitListener(ClusterStateListener listener) {
        clusterNode.getApplierService().addPreCommitListener(listener);
    }

    public ClusterState state() {
        return clusterNode.getApplierService().state();
    }

    public boolean isLeader() {
        return clusterNode.getCoordinator().isLeader();
    }

    public void start(long electionTimeoutMillis) throws TimeoutException {
        started = true;
        scheduler.scheduleWithFixedDelay(this::tickSafely, 0L, tickIntervalMillis, TimeUnit.MILLISECONDS);
        long deadline = System.currentTimeMillis() + electionTimeoutMillis;
        while (!hasElectedMaster()) {
            if (System.currentTimeMillis() > deadline) {
                if (isMultiNode()) {
                    LOG.warn("[cluster] node [" + localNode.getName() + "] has not discovered an elected master within ["
                        + electionTimeoutMillis + "ms]; continuing to look for one in the background");
                    return;
                }
                throw new TimeoutException("node [" + localNode.getName() + "] failed to elect itself master within ["
                    + electionTimeoutMillis + "ms]");
            }
            try {
                Thread.sleep(5L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TimeoutException("interrupted while waiting for master election");
            }
        }
    }

    private boolean hasElectedMaster() {
        String master = state().getNodes().getMasterNodeId();
        if (master == null) {
            return false;
        }
        return isMultiNode() || isLeader();
    }

    public boolean awaitMaster(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (state().getNodes().getMasterNodeId() == null || !state().getNodes().nodeExists(localNode.getId())) {
            if (System.currentTimeMillis() > deadline) {
                return false;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    private void tickSafely() {
        try {
            tick(System.currentTimeMillis());
        } catch (Throwable t) {
            LOG.warn("[cluster] tick failed: " + t);
        }
    }

    public void tick(long nowMillis) {
        synchronized (tickLock) {
            if (loopback != null) {
                loopback.drain();
            }
            clusterNode.tick(nowMillis);
            if (loopback != null) {
                loopback.drain();
            }
        }
        if (isMultiNode() && isLeader() && !reconcilePending && nowMillis - lastReconcileAt >= 1_000L) {
            lastReconcileAt = nowMillis;
            scheduleReconcile("periodic-reconcile");
        }
    }

    public CompletableFuture<ClusterState> submit(String source, UnaryOperator<ClusterState> update) {
        if (!isMultiNode() || isLeader()) {
            return submitLocal(source, update);
        }
        CompletableFuture<ClusterState> future = new CompletableFuture<>();
        try {
            forwarder.execute(() -> forward(source, update, future));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            future.completeExceptionally(new IllegalStateException("node is shutting down"));
        }
        return future;
    }

    private CompletableFuture<ClusterState> submitLocal(String source, UnaryOperator<ClusterState> update) {
        UpdateTask task = new UpdateTask(source, update);
        clusterNode.submitTask(source, task, Priority.NORMAL, executor, new ClusterStateTaskExecutor.TaskListener<>() {
            @Override
            public void onSuccess(UpdateTask t, ClusterState newState) {
                if (t.failure != null) {
                    t.future.completeExceptionally(t.failure);
                } else {
                    t.future.complete(newState);
                }
            }

            @Override
            public void onFailure(UpdateTask t, Exception e) {
                t.future.completeExceptionally(t.failure != null ? t.failure : e);
            }
        }, System.currentTimeMillis());
        return task.future;
    }

    private void forward(String source, UnaryOperator<ClusterState> update, CompletableFuture<ClusterState> future) {
        long deadline = System.currentTimeMillis() + config.forwardTimeoutMillis();
        long waitForVersion = -1L;
        while (!closed) {
            if (System.currentTimeMillis() > deadline) {
                future.completeExceptionally(new IllegalStateException("timed out forwarding cluster state update [" + source
                    + "] to the elected master"));
                return;
            }
            if (isLeader()) {
                submitLocal(source, update).whenComplete((s, e) -> {
                    if (e != null) {
                        future.completeExceptionally(Wire.unwrap(e));
                    } else {
                        future.complete(s);
                    }
                });
                return;
            }
            ClusterState base = state();
            DiscoveryNode master = base.getNodes().getMasterNode();
            if (master == null || base.getVersion() < waitForVersion || master.getId().equals(localNode.getId())) {
                sleep(20L);
                continue;
            }
            ClusterState proposed;
            try {
                proposed = update.apply(base);
            } catch (RuntimeException e) {
                future.completeExceptionally(e);
                return;
            }
            if (proposed == null || proposed == base || (proposed.getMetadata() == base.getMetadata()
                && proposed.getRoutingTable() == base.getRoutingTable() && proposed.getBlocks() == base.getBlocks())) {
                future.complete(base);
                return;
            }
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("source", source);
            request.put("base_uuid", base.getStateUUID());
            request.put("metadata", Wire.serialize(proposed.getMetadata()));
            request.put("routing", Wire.serialize(proposed.getRoutingTable()));
            request.put("blocks", Wire.serialize(proposed.getBlocks()));
            Map<String, Object> response;
            try {
                Connection connection = connections.get(master);
                response = Wire.decode(Wire.sendSync(transportService, connection, PROPOSE_ACTION, Wire.encode(request),
                    Math.max(1_000L, deadline - System.currentTimeMillis())));
            } catch (Exception e) {
                Throwable cause = Wire.unwrap(e);
                if (cause instanceof com.naqqa.elasticsearch.transport.RemoteTransportException rte
                    && !rte.remoteExceptionClassName().endsWith("NotMasterException")) {
                    future.completeExceptionally(remoteFailure(rte));
                    return;
                }
                sleep(50L);
                continue;
            }
            long version = ((Number) response.get("version")).longValue();
            if (Boolean.TRUE.equals(response.get("accepted"))) {
                awaitVersion(version, Math.min(deadline, System.currentTimeMillis() + 10_000L));
                future.complete(state());
                return;
            }
            waitForVersion = version;
        }
        future.completeExceptionally(new IllegalStateException("node closed"));
    }

    private static RuntimeException remoteFailure(com.naqqa.elasticsearch.transport.RemoteTransportException rte) {
        String cls = rte.remoteExceptionClassName();
        String message = rte.getMessage();
        int marker = message.indexOf("]: ");
        String reason = marker >= 0 ? message.substring(marker + 3) : message;
        int status = 500;
        if (cls.endsWith("ResourceAlreadyExistsException")) {
            status = 400;
        } else if (cls.endsWith("IndexNotFoundException")) {
            status = 404;
        } else if (cls.endsWith("IllegalArgumentException") || cls.endsWith("RestApiException")) {
            status = 400;
        }
        return new com.naqqa.elasticsearch.rest.support.RestApiException(status, reason, rte);
    }

    private void awaitVersion(long version, long deadline) {
        while (state().getVersion() < version && System.currentTimeMillis() < deadline && !closed) {
            sleep(5L);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static final class NotMasterException extends RuntimeException {
        NotMasterException(String message) {
            super(message);
        }
    }

    private CompletableFuture<byte[]> handleProposal(byte[] payload) throws Exception {
        if (!isLeader()) {
            throw new NotMasterException("node [" + localNode.getName() + "] is not the elected master");
        }
        Map<String, Object> request = Wire.decode(payload);
        String baseUuid = String.valueOf(request.get("base_uuid"));
        Metadata metadata = Metadata.readFrom(StreamUtils.toInput((byte[]) request.get("metadata")));
        RoutingTable routing = RoutingTable.readFrom(StreamUtils.toInput((byte[]) request.get("routing")));
        ClusterBlocks blocks = ClusterBlocks.readFrom(StreamUtils.toInput((byte[]) request.get("blocks")));
        String source = "remote:" + request.get("source");
        CompletableFuture<byte[]> response = new CompletableFuture<>();
        submitLocal(source, current -> {
            if (!current.getStateUUID().equals(baseUuid)) {
                throw new StaleBaseException(current.getVersion());
            }
            Metadata merged = metadata.toBuilder().coordinationMetadata(current.getMetadata().coordinationMetadata()).build();
            return current.builder().metadata(merged).routingTable(routing).blocks(blocks).build();
        }).whenComplete((state, error) -> {
            Map<String, Object> out = new LinkedHashMap<>();
            if (error != null) {
                Throwable cause = Wire.unwrap(error);
                if (cause instanceof StaleBaseException stale) {
                    out.put("accepted", false);
                    out.put("version", stale.masterVersion());
                    response.complete(Wire.encode(out));
                } else {
                    response.completeExceptionally(cause);
                }
                return;
            }
            out.put("accepted", true);
            out.put("version", state.getVersion());
            response.complete(Wire.encode(out));
        });
        return response;
    }

    private ClusterState executeBatch(ClusterState current, List<UpdateTask> tasks) {
        ClusterState state = current;
        for (UpdateTask task : tasks) {
            try {
                ClusterState next = task.update.apply(state);
                if (next != null && next != state) {
                    state = next.builder().stateUUID(NodeIdentity.generate()).build();
                }
            } catch (Exception e) {
                task.failure = e;
            }
        }
        if (state == current) {
            return current;
        }
        return state.builder().incrementVersion().version(current.getVersion() + 1).build();
    }

    @Override
    public void clusterChanged(ClusterChangedEvent event) {
        if (!isMultiNode() || !isLeader()) {
            return;
        }
        if (event.state().getNodes() != event.previousState().getNodes() || needsReconcile(event.state())) {
            scheduleReconcile("nodes-changed");
        }
        com.naqqa.elasticsearch.cluster.state.CoordinationMetadata coordination = event.state().getMetadata().coordinationMetadata();
        if (!coordination.getLastCommittedConfiguration().equals(coordination.getLastAcceptedConfiguration())) {
            String committedUuid = event.state().getStateUUID();
            submitLocal("commit-voting-configuration", current -> {
                if (!current.getStateUUID().equals(committedUuid)) {
                    return current;
                }
                com.naqqa.elasticsearch.cluster.state.CoordinationMetadata cm = current.getMetadata().coordinationMetadata();
                if (cm.getLastCommittedConfiguration().equals(cm.getLastAcceptedConfiguration())) {
                    return current;
                }
                return current.builder().metadata(current.getMetadata().toBuilder()
                    .coordinationMetadata(cm.withLastCommittedConfiguration(cm.getLastAcceptedConfiguration())).build()).build();
            });
        }
    }

    private void scheduleReconcile(String source) {
        if (closed) {
            return;
        }
        reconcilePending = true;
        submitLocal(source, this::reconcile).whenComplete((s, e) -> reconcilePending = false);
    }

    private static boolean needsReconcile(ClusterState state) {
        DiscoveryNodes nodes = state.getNodes();
        for (ShardRouting sr : state.getRoutingTable().allShards()) {
            if (sr.currentNodeId() != null && !nodes.nodeExists(sr.currentNodeId())) {
                return true;
            }
            if (sr.unassigned()) {
                return true;
            }
        }
        return false;
    }

    ClusterState reconcile(ClusterState current) {
        long now = System.currentTimeMillis();
        DiscoveryNodes nodes = current.getNodes();
        Set<String> dead = new LinkedHashSet<>();
        for (ShardRouting sr : current.getRoutingTable().allShards()) {
            if (sr.currentNodeId() != null && !nodes.nodeExists(sr.currentNodeId())) {
                dead.add(sr.currentNodeId());
            }
            if (sr.relocatingNodeId() != null && !nodes.nodeExists(sr.relocatingNodeId())) {
                dead.add(sr.relocatingNodeId());
            }
        }
        ClusterState state = current;
        if (!dead.isEmpty()) {
            state = allocationService.deassociateDeadNodes(state, new ArrayList<>(dead), now);
            state = demoteDuplicatePrimaries(state, now);
        }
        state = undelayReturningNodes(state);
        state = allocationService.reroute(state, "reconcile", now);
        state = pruneInSync(state);
        if (sameRouting(state.getRoutingTable(), current.getRoutingTable()) && state.getMetadata() == current.getMetadata()) {
            return current;
        }
        return state;
    }

    private static ClusterState demoteDuplicatePrimaries(ClusterState state, long now) {
        RoutingTable.Builder rb = state.getRoutingTable().toBuilder();
        boolean changed = false;
        for (IndexRoutingTable irt : state.getRoutingTable().getIndicesRouting().values()) {
            IndexRoutingTable.Builder ib = irt.builder();
            boolean indexChanged = false;
            IndexMetadata imd = state.getMetadata().index(irt.getIndex());
            long delay = imd == null ? 60_000L : imd.getSettings().getAsLong("index.unassigned.node_left.delayed_timeout", 60_000L);
            for (IndexShardRoutingTable table : irt.getShards().values()) {
                boolean hasActivePrimary = false;
                for (ShardRouting sr : table.getShards()) {
                    if (sr.primary() && sr.active()) {
                        hasActivePrimary = true;
                    }
                }
                if (!hasActivePrimary) {
                    continue;
                }
                List<ShardRouting> copies = new ArrayList<>();
                boolean tableChanged = false;
                for (ShardRouting sr : table.getShards()) {
                    if (sr.primary() && sr.unassigned()) {
                        UnassignedInfo old = sr.unassignedInfo();
                        UnassignedInfo info = new UnassignedInfo(UnassignedInfo.Reason.NODE_LEFT,
                            old == null ? "primary copy lost" : old.getMessage(), now, 0, delay > 0,
                            old == null ? null : old.getLastAllocatedNodeId());
                        copies.add(sr.moveToUnassigned(info).movePrimaryFlag(false));
                        tableChanged = true;
                    } else {
                        copies.add(sr);
                    }
                }
                if (tableChanged) {
                    ib.putShardTable(table.withShards(copies));
                    indexChanged = true;
                }
            }
            if (indexChanged) {
                rb.add(ib.build());
                changed = true;
            }
        }
        return changed ? state.builder().routingTable(rb.build()).build() : state;
    }

    private static ClusterState undelayReturningNodes(ClusterState state) {
        RoutingTable.Builder rb = state.getRoutingTable().toBuilder();
        boolean changed = false;
        for (IndexRoutingTable irt : state.getRoutingTable().getIndicesRouting().values()) {
            IndexRoutingTable.Builder ib = irt.builder();
            boolean indexChanged = false;
            for (IndexShardRoutingTable table : irt.getShards().values()) {
                List<ShardRouting> copies = new ArrayList<>();
                boolean tableChanged = false;
                for (ShardRouting sr : table.getShards()) {
                    UnassignedInfo info = sr.unassignedInfo();
                    if (sr.unassigned() && info != null && info.isDelayed() && info.getLastAllocatedNodeId() != null
                        && state.getNodes().nodeExists(info.getLastAllocatedNodeId())) {
                        copies.add(sr.moveToUnassigned(info.withDelayed(false)));
                        tableChanged = true;
                    } else {
                        copies.add(sr);
                    }
                }
                if (tableChanged) {
                    ib.putShardTable(table.withShards(copies));
                    indexChanged = true;
                }
            }
            if (indexChanged) {
                rb.add(ib.build());
                changed = true;
            }
        }
        return changed ? state.builder().routingTable(rb.build()).build() : state;
    }

    private static ClusterState pruneInSync(ClusterState state) {
        Metadata.Builder mb = null;
        for (IndexRoutingTable irt : state.getRoutingTable().getIndicesRouting().values()) {
            IndexMetadata imd = state.getMetadata().index(irt.getIndex());
            if (imd == null) {
                continue;
            }
            IndexMetadata.Builder builder = null;
            for (IndexShardRoutingTable table : irt.getShards().values()) {
                Set<String> assigned = new HashSet<>();
                boolean anyActive = false;
                for (ShardRouting sr : table.getShards()) {
                    if (sr.allocationId() != null) {
                        assigned.add(sr.allocationId().getId());
                    }
                    anyActive |= sr.active();
                }
                Set<String> inSync = imd.inSyncAllocationIds(table.getShardId().id());
                if (!anyActive || inSync.isEmpty() || assigned.containsAll(inSync)) {
                    continue;
                }
                Set<String> pruned = new LinkedHashSet<>(inSync);
                pruned.retainAll(assigned);
                if (pruned.isEmpty()) {
                    continue;
                }
                if (builder == null) {
                    builder = imd.builder();
                }
                builder.putInSyncAllocationIds(table.getShardId().id(), pruned);
            }
            if (builder != null) {
                if (mb == null) {
                    mb = state.getMetadata().toBuilder();
                }
                mb.put(builder.build());
            }
        }
        return mb == null ? state : state.builder().metadata(mb.build()).build();
    }

    private static boolean sameRouting(RoutingTable a, RoutingTable b) {
        if (!a.getIndicesRouting().keySet().equals(b.getIndicesRouting().keySet())) {
            return false;
        }
        List<ShardRouting> left = a.allShards();
        List<ShardRouting> right = b.allShards();
        if (left.size() != right.size()) {
            return false;
        }
        Map<ShardRouting, Integer> counts = new LinkedHashMap<>();
        for (ShardRouting sr : left) {
            counts.merge(sr, 1, Integer::sum);
        }
        for (ShardRouting sr : right) {
            Integer c = counts.get(sr);
            if (c == null) {
                return false;
            }
            if (c == 1) {
                counts.remove(sr);
            } else {
                counts.put(sr, c - 1);
            }
        }
        for (ShardRouting sr : left) {
            ShardRouting match = null;
            for (ShardRouting other : right) {
                if (other.equals(sr)) {
                    match = other;
                    break;
                }
            }
            if (match != null && !Objects.equals(delayed(sr), delayed(match))) {
                return false;
            }
        }
        return counts.isEmpty();
    }

    private static Boolean delayed(ShardRouting sr) {
        return sr.unassignedInfo() == null ? null : sr.unassignedInfo().isDelayed();
    }

    @Override
    public void close() {
        closed = true;
        scheduler.shutdownNow();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        forwarder.shutdownNow();
        if (transport instanceof TransportClusterTransport tct) {
            tct.close();
        }
    }
}
