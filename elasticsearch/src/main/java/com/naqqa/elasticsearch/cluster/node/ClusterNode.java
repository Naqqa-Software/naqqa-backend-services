package com.naqqa.elasticsearch.cluster.node;

import com.naqqa.elasticsearch.cluster.coordination.Coordinator;
import com.naqqa.elasticsearch.cluster.discovery.ClusterTransport;
import com.naqqa.elasticsearch.cluster.discovery.SeedHostsProvider;
import com.naqqa.elasticsearch.cluster.routing.allocation.AllocationService;
import com.naqqa.elasticsearch.cluster.service.ClusterApplierService;
import com.naqqa.elasticsearch.cluster.service.ClusterStateTaskConfig;
import com.naqqa.elasticsearch.cluster.service.ClusterStateTaskExecutor;
import com.naqqa.elasticsearch.cluster.service.MasterService;
import com.naqqa.elasticsearch.cluster.service.Priority;
import com.naqqa.elasticsearch.cluster.state.ClusterState;

import java.nio.file.Path;
import java.util.List;

public final class ClusterNode {

    private final DiscoveryNode discoveryNode;
    private final Coordinator coordinator;
    private final MasterService masterService;
    private final ClusterApplierService applierService;
    private final AllocationService allocationService;

    public ClusterNode(String clusterName, DiscoveryNode discoveryNode, ClusterTransport transport, Path dataDir,
                        List<SeedHostsProvider> seedHostsProviders, List<String> initialMasterNodeNames,
                        long peerFinderIntervalMillis, long checkIntervalMillis, long checkTimeoutMillis,
                        int checkMaxFailures, long electionCooldownMillis, long electionTimeoutMillis,
                        AllocationService allocationService) {
        this.discoveryNode = discoveryNode;
        this.allocationService = allocationService;
        this.applierService = new ClusterApplierService(discoveryNode.getId(), ClusterState.builder(clusterName).build());
        this.coordinator = new Coordinator(discoveryNode, transport, dataDir, seedHostsProviders,
            initialMasterNodeNames, peerFinderIntervalMillis, checkIntervalMillis, checkTimeoutMillis,
            checkMaxFailures, electionCooldownMillis, electionTimeoutMillis,
            state -> applierService.onNewClusterState("coordinator", state));
        this.masterService = new MasterService(coordinator::isLeader, coordinator::getClusterState,
            (newState, callback) -> coordinator.publish(newState, new Coordinator.PublishListener() {
                @Override
                public void onResponse(ClusterState committedState) {
                    callback.onResponse(committedState);
                }

                @Override
                public void onFailure(Exception e) {
                    callback.onFailure(e);
                }
            }));
    }

    public DiscoveryNode getDiscoveryNode() {
        return discoveryNode;
    }

    public Coordinator getCoordinator() {
        return coordinator;
    }

    public MasterService getMasterService() {
        return masterService;
    }

    public ClusterApplierService getApplierService() {
        return applierService;
    }

    public AllocationService getAllocationService() {
        return allocationService;
    }

    public ClusterState getClusterState() {
        return coordinator.getClusterState();
    }

    public void tick(long nowMillis) {
        coordinator.tick(nowMillis);
        masterService.tick(nowMillis);
    }

    public <T> void submitTask(String source, T task, Priority priority, ClusterStateTaskExecutor<T> executor,
                                ClusterStateTaskExecutor.TaskListener<T> listener, long nowMillis) {
        masterService.submitTask(source, task, ClusterStateTaskConfig.of(priority), executor, listener, nowMillis);
    }
}
