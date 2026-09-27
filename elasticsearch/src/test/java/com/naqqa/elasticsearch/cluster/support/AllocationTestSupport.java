package com.naqqa.elasticsearch.cluster.support;

import com.naqqa.elasticsearch.cluster.node.DiscoveryNode;
import com.naqqa.elasticsearch.cluster.node.DiscoveryNodeRole;
import com.naqqa.elasticsearch.cluster.node.DiscoveryNodes;
import com.naqqa.elasticsearch.cluster.routing.IndexRoutingTable;
import com.naqqa.elasticsearch.cluster.routing.RoutingTable;
import com.naqqa.elasticsearch.cluster.routing.allocation.AllocationDeciders;
import com.naqqa.elasticsearch.cluster.routing.allocation.AllocationService;
import com.naqqa.elasticsearch.cluster.routing.allocation.BalancedShardsAllocator;
import com.naqqa.elasticsearch.cluster.routing.allocation.DiskUsageProvider;
import com.naqqa.elasticsearch.cluster.routing.allocation.decider.AwarenessAllocationDecider;
import com.naqqa.elasticsearch.cluster.routing.allocation.decider.DataTierAllocationDecider;
import com.naqqa.elasticsearch.cluster.routing.allocation.decider.DiskThresholdDecider;
import com.naqqa.elasticsearch.cluster.routing.allocation.decider.EnableAllocationDecider;
import com.naqqa.elasticsearch.cluster.routing.allocation.decider.FilterAllocationDecider;
import com.naqqa.elasticsearch.cluster.routing.allocation.decider.MaxRetryAllocationDecider;
import com.naqqa.elasticsearch.cluster.routing.allocation.decider.NodeVersionAllocationDecider;
import com.naqqa.elasticsearch.cluster.routing.allocation.decider.SameShardAllocationDecider;
import com.naqqa.elasticsearch.cluster.routing.allocation.decider.ThrottlingAllocationDecider;
import com.naqqa.elasticsearch.cluster.state.ClusterState;
import com.naqqa.elasticsearch.cluster.state.IndexMetadata;
import com.naqqa.elasticsearch.cluster.state.Metadata;
import com.naqqa.elasticsearch.cluster.state.Settings;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

public final class AllocationTestSupport {

    private AllocationTestSupport() {
    }

    public static DiscoveryNode dataNode(String id, Map<String, String> attributes) {
        return new DiscoveryNode(id, id, id + ":9300", attributes,
            EnumSet.of(DiscoveryNodeRole.DATA, DiscoveryNodeRole.MASTER), 1L);
    }

    public static DiscoveryNode dataNode(String id, Map<String, String> attributes, long version) {
        return new DiscoveryNode(id, id, id + ":9300", attributes,
            EnumSet.of(DiscoveryNodeRole.DATA, DiscoveryNodeRole.MASTER), version);
    }

    public static ClusterState clusterStateWithIndex(List<DiscoveryNode> nodes, String index, int shards,
                                                       int replicas) {
        return clusterStateWithIndex(nodes, index, shards, replicas, Settings.EMPTY);
    }

    public static ClusterState clusterStateWithIndex(List<DiscoveryNode> nodes, String index, int shards,
                                                       int replicas, Settings extraSettings) {
        DiscoveryNodes.Builder nodesBuilder = DiscoveryNodes.builder();
        for (DiscoveryNode node : nodes) {
            nodesBuilder.add(node);
        }
        if (!nodes.isEmpty()) {
            nodesBuilder.masterNodeId(nodes.get(0).getId());
        }
        Settings settings = Settings.builder()
            .put("index.number_of_shards", shards)
            .put("index.number_of_replicas", replicas)
            .putAll(extraSettings)
            .build();
        IndexMetadata indexMetadata = IndexMetadata.builder(index).settings(settings).build();
        Metadata metadata = Metadata.builder().put(indexMetadata).build();
        RoutingTable routingTable = RoutingTable.builder()
            .add(IndexRoutingTable.builder(index).initializeAsNew(index, shards, replicas, 0L).build())
            .build();
        return ClusterState.builder("test").nodes(nodesBuilder.build()).metadata(metadata).routingTable(routingTable).build();
    }

    public static AllocationDeciders defaultDeciders() {
        return new AllocationDeciders(List.of(
            new SameShardAllocationDecider(),
            new EnableAllocationDecider(),
            new FilterAllocationDecider(),
            new AwarenessAllocationDecider(),
            new DiskThresholdDecider(),
            new MaxRetryAllocationDecider(),
            new ThrottlingAllocationDecider(),
            new NodeVersionAllocationDecider(),
            new DataTierAllocationDecider()));
    }

    public static AllocationService defaultAllocationService(DiskUsageProvider diskUsageProvider) {
        AllocationDeciders deciders = defaultDeciders();
        return new AllocationService(deciders, new BalancedShardsAllocator(deciders), diskUsageProvider);
    }

    public static AllocationService defaultAllocationService() {
        return defaultAllocationService(DiskUsageProvider.NONE);
    }

    public static ClusterState startInitializingShards(AllocationService service, ClusterState state, long nowMillis) {
        for (int i = 0; i < 10; i++) {
            List<com.naqqa.elasticsearch.cluster.routing.ShardRouting> initializing = new java.util.ArrayList<>();
            for (com.naqqa.elasticsearch.cluster.routing.ShardRouting shard : state.getRoutingTable().allShards()) {
                if (shard.initializing()) {
                    initializing.add(shard);
                }
            }
            if (initializing.isEmpty()) {
                break;
            }
            state = service.applyStartedShards(state, initializing, nowMillis);
        }
        return state;
    }

    public static ClusterState addIndex(ClusterState state, String index, int shards, int replicas) {
        Settings settings = Settings.builder()
            .put("index.number_of_shards", shards)
            .put("index.number_of_replicas", replicas)
            .build();
        IndexMetadata indexMetadata = IndexMetadata.builder(index).settings(settings).build();
        Metadata metadata = state.getMetadata().toBuilder().put(indexMetadata).build();
        RoutingTable routingTable = state.getRoutingTable().toBuilder()
            .add(IndexRoutingTable.builder(index).initializeAsNew(index, shards, replicas, 0L).build())
            .build();
        return state.builder().metadata(metadata).routingTable(routingTable).build();
    }
}
