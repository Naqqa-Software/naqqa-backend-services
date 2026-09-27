package com.naqqa.elasticsearch.node.cluster;

import com.naqqa.elasticsearch.cluster.discovery.SeedHostsProvider;
import com.naqqa.elasticsearch.cluster.discovery.StaticSeedHostsProvider;
import com.naqqa.elasticsearch.cluster.node.ClusterNode;
import com.naqqa.elasticsearch.cluster.node.DiscoveryNode;
import com.naqqa.elasticsearch.cluster.routing.allocation.AllocationDeciders;
import com.naqqa.elasticsearch.cluster.routing.allocation.AllocationService;
import com.naqqa.elasticsearch.cluster.routing.allocation.BalancedShardsAllocator;
import com.naqqa.elasticsearch.cluster.routing.allocation.DiskUsageProvider;
import com.naqqa.elasticsearch.cluster.routing.allocation.decider.EnableAllocationDecider;
import com.naqqa.elasticsearch.cluster.routing.allocation.decider.FilterAllocationDecider;
import com.naqqa.elasticsearch.cluster.routing.allocation.decider.MaxRetryAllocationDecider;
import com.naqqa.elasticsearch.cluster.routing.allocation.decider.SameShardAllocationDecider;
import com.naqqa.elasticsearch.cluster.service.ClusterApplierService;
import com.naqqa.elasticsearch.cluster.service.ClusterStateApplier;
import com.naqqa.elasticsearch.cluster.service.ClusterStateListener;
import com.naqqa.elasticsearch.cluster.service.ClusterStateTaskExecutor;
import com.naqqa.elasticsearch.cluster.service.MasterService;
import com.naqqa.elasticsearch.cluster.service.Priority;
import com.naqqa.elasticsearch.cluster.state.ClusterState;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.UnaryOperator;

public final class ClusterStateManager implements AutoCloseable {

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

    private final String clusterName;
    private final DiscoveryNode localNode;
    private final LoopbackClusterTransport transport;
    private final ClusterNode clusterNode;
    private final AllocationService allocationService;
    private final long tickIntervalMillis;
    private final Object tickLock = new Object();
    private final ClusterStateTaskExecutor<UpdateTask> executor = this::executeBatch;
    private volatile ScheduledExecutorService ticker;

    public ClusterStateManager(String clusterName, DiscoveryNode localNode, Path stateDir, long tickIntervalMillis) {
        this.clusterName = clusterName;
        this.localNode = localNode;
        this.tickIntervalMillis = tickIntervalMillis;
        this.transport = new LoopbackClusterTransport(localNode);
        AllocationDeciders deciders = new AllocationDeciders(List.of(new SameShardAllocationDecider(),
            new MaxRetryAllocationDecider(), new FilterAllocationDecider(), new EnableAllocationDecider()));
        this.allocationService = new AllocationService(deciders, new BalancedShardsAllocator(deciders), DiskUsageProvider.NONE);
        List<SeedHostsProvider> seeds = List.of(new StaticSeedHostsProvider(List.of(localNode.getAddress())));
        this.clusterNode = new ClusterNode(clusterName, localNode, transport, stateDir, seeds,
            List.of(localNode.getName()), 1000L, 1000L, 10_000L, 3, 50L, 5_000L, allocationService);
    }

    public String clusterName() {
        return clusterName;
    }

    public DiscoveryNode localNode() {
        return localNode;
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

    public ClusterState state() {
        return clusterNode.getApplierService().state();
    }

    public boolean isLeader() {
        return clusterNode.getCoordinator().isLeader();
    }

    public void start(long electionTimeoutMillis) throws TimeoutException {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cluster-coordinator[" + localNode.getName() + "]");
            t.setDaemon(true);
            return t;
        });
        this.ticker = scheduler;
        scheduler.scheduleWithFixedDelay(this::tickSafely, 0L, tickIntervalMillis, TimeUnit.MILLISECONDS);
        long deadline = System.currentTimeMillis() + electionTimeoutMillis;
        while (!isLeader() || state().getNodes().getMasterNodeId() == null) {
            if (System.currentTimeMillis() > deadline) {
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

    private void tickSafely() {
        try {
            tick(System.currentTimeMillis());
        } catch (Throwable t) {
            System.err.println("[cluster] tick failed: " + t);
        }
    }

    public void tick(long nowMillis) {
        synchronized (tickLock) {
            transport.drain();
            clusterNode.tick(nowMillis);
            transport.drain();
        }
    }

    public CompletableFuture<ClusterState> submit(String source, UnaryOperator<ClusterState> update) {
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

    private ClusterState executeBatch(ClusterState current, List<UpdateTask> tasks) {
        ClusterState state = current;
        for (UpdateTask task : tasks) {
            try {
                ClusterState next = task.update.apply(state);
                if (next != null) {
                    state = next;
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
    public void close() {
        ScheduledExecutorService scheduler = ticker;
        if (scheduler != null) {
            scheduler.shutdownNow();
            try {
                scheduler.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
