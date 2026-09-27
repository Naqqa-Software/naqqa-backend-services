package com.naqqa.elasticsearch.cluster.routing.allocation;

import com.naqqa.elasticsearch.cluster.node.DiscoveryNode;
import com.naqqa.elasticsearch.cluster.routing.ShardRouting;
import com.naqqa.elasticsearch.cluster.support.AllocationTestSupport;
import com.naqqa.elasticsearch.cluster.state.ClusterState;
import com.naqqa.elasticsearch.cluster.state.Metadata;
import com.naqqa.elasticsearch.cluster.state.Settings;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Map;

public final class DiskThresholdDeciderTest {

    private static final class FixedDiskUsageProvider implements DiskUsageProvider {
        private final Map<String, DiskUsage> usages;

        FixedDiskUsageProvider(Map<String, DiskUsage> usages) {
            this.usages = usages;
        }

        @Override
        public Map<String, DiskUsage> getDiskUsages() {
            return usages;
        }

        @Override
        public long getShardSizeBytes(String index, int shardId) {
            return 0L;
        }
    }

    @Test
    public void testShardIsNotAllocatedToNodeAboveHighWatermark() {
        DiscoveryNode full = AllocationTestSupport.dataNode("full", Map.of());
        DiscoveryNode empty = AllocationTestSupport.dataNode("empty", Map.of());
        ClusterState state = AllocationTestSupport.clusterStateWithIndex(List.of(full, empty), "idx", 1, 0);

        Settings clusterSettings = Settings.builder()
            .put(com.naqqa.elasticsearch.cluster.routing.allocation.decider.DiskThresholdDecider.HIGH_KEY, "85%")
            .build();
        Metadata metadata = state.getMetadata().toBuilder().persistentSettings(clusterSettings).build();
        state = state.builder().metadata(metadata).build();

        DiskUsageProvider diskUsageProvider = new FixedDiskUsageProvider(Map.of(
            "full", new DiskUsage("full", 100L, 5L),
            "empty", new DiskUsage("empty", 100L, 90L)));
        AllocationDeciders deciders = AllocationTestSupport.defaultDeciders();
        AllocationService service = new AllocationService(deciders, new BalancedShardsAllocator(deciders), diskUsageProvider);

        state = service.reroute(state, "initial", 0L);
        state = AllocationTestSupport.startInitializingShards(service, state, 0L);
        ShardRouting shard = state.getRoutingTable().index("idx").shard(0).primaryShard();
        Assert.assertTrue(shard.started(), "expected the shard to be allocated to the node with free disk space");
        Assert.assertEquals("empty", shard.currentNodeId());
    }

    @Test
    public void testDeciderReturnsNoWhenAllocatingWouldBreachHighWatermark() {
        com.naqqa.elasticsearch.cluster.routing.allocation.decider.DiskThresholdDecider decider =
            new com.naqqa.elasticsearch.cluster.routing.allocation.decider.DiskThresholdDecider();
        DiscoveryNode full = AllocationTestSupport.dataNode("full", Map.of());
        ClusterState state = AllocationTestSupport.clusterStateWithIndex(List.of(full), "idx", 1, 0);
        Settings clusterSettings = Settings.builder()
            .put(com.naqqa.elasticsearch.cluster.routing.allocation.decider.DiskThresholdDecider.HIGH_KEY, "80%")
            .build();
        Metadata metadata = state.getMetadata().toBuilder().persistentSettings(clusterSettings).build();
        state = state.builder().metadata(metadata).build();

        DiskUsageProvider diskUsageProvider = new FixedDiskUsageProvider(Map.of("full", new DiskUsage("full", 100L, 5L)));
        com.naqqa.elasticsearch.cluster.routing.RoutingNodes routingNodes = new com.naqqa.elasticsearch.cluster.routing.RoutingNodes(
            state.getRoutingTable(), state.getNodes());
        RoutingAllocation allocation = new RoutingAllocation(routingNodes, state.getMetadata(), state.getNodes(), 0L,
            diskUsageProvider);
        ShardRouting shard = routingNodes.unassigned().get(0);
        Decision decision = decider.canAllocate(shard, routingNodes.node("full"), allocation);
        Assert.assertEquals(Decision.Type.NO, decision.type());
    }
}
