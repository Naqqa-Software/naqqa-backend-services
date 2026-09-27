package com.naqqa.elasticsearch.cluster.routing.allocation;

import com.naqqa.elasticsearch.cluster.node.DiscoveryNode;
import com.naqqa.elasticsearch.cluster.routing.IndexShardRoutingTable;
import com.naqqa.elasticsearch.cluster.routing.ShardRouting;
import com.naqqa.elasticsearch.cluster.routing.allocation.decider.SameShardAllocationDecider;
import com.naqqa.elasticsearch.cluster.support.AllocationTestSupport;
import com.naqqa.elasticsearch.cluster.state.ClusterState;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class BalancedShardsAllocatorTest {

    @Test
    public void testPrimariesAndReplicasSpreadAcrossNodes() {
        List<DiscoveryNode> nodes = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            nodes.add(AllocationTestSupport.dataNode("n" + i, Map.of()));
        }
        ClusterState state = AllocationTestSupport.clusterStateWithIndex(nodes, "idx", 4, 1);
        AllocationService service = AllocationTestSupport.defaultAllocationService();
        state = service.reroute(state, "initial", 0L);
        state = AllocationTestSupport.startInitializingShards(service, state, 0L);

        Map<String, Integer> shardsPerNode = new HashMap<>();
        for (ShardRouting shard : state.getRoutingTable().allShards()) {
            Assert.assertTrue(shard.started(), "expected all shards to be started after reroute: " + shard);
            shardsPerNode.merge(shard.currentNodeId(), 1, Integer::sum);
        }
        Assert.assertEquals(4, shardsPerNode.size(), "expected all four nodes to hold at least one shard");
        int max = shardsPerNode.values().stream().max(Integer::compareTo).orElse(0);
        int min = shardsPerNode.values().stream().min(Integer::compareTo).orElse(0);
        Assert.assertTrue(max - min <= 1, "expected a balanced shard distribution, got " + shardsPerNode);
    }

    @Test
    public void testSameShardDeciderPreventsCoLocationOfPrimaryAndReplica() {
        List<DiscoveryNode> nodes = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            nodes.add(AllocationTestSupport.dataNode("n" + i, Map.of()));
        }
        ClusterState state = AllocationTestSupport.clusterStateWithIndex(nodes, "idx", 1, 1);
        AllocationService service = AllocationTestSupport.defaultAllocationService();
        state = service.reroute(state, "initial", 0L);

        IndexShardRoutingTable shardTable = state.getRoutingTable().index("idx").shard(0);
        Assert.assertEquals(2, shardTable.getShards().size());
        ShardRouting primary = shardTable.primaryShard();
        ShardRouting replica = shardTable.replicaShards().get(0);
        Assert.assertNotNull(primary.currentNodeId());
        Assert.assertNotNull(replica.currentNodeId());
        Assert.assertNotEquals(primary.currentNodeId(), replica.currentNodeId());
    }

    @Test
    public void testSameShardDeciderRejectsSecondCopyOnSameNode() {
        SameShardAllocationDecider decider = new SameShardAllocationDecider();
        List<DiscoveryNode> nodes = List.of(AllocationTestSupport.dataNode("n0", Map.of()));
        ClusterState state = AllocationTestSupport.clusterStateWithIndex(nodes, "idx", 1, 1);
        com.naqqa.elasticsearch.cluster.routing.RoutingNodes routingNodes = new com.naqqa.elasticsearch.cluster.routing.RoutingNodes(
            state.getRoutingTable(), state.getNodes());
        ShardRouting unassignedReplica = routingNodes.unassigned().stream().filter(s -> !s.primary()).findFirst().get();
        ShardRouting unassignedPrimary = routingNodes.unassigned().stream().filter(ShardRouting::primary).findFirst().get();
        routingNodes.initializeShard(unassignedPrimary, "n0", -1L);
        RoutingAllocation allocation = new RoutingAllocation(routingNodes, state.getMetadata(), state.getNodes(), 0L,
            DiskUsageProvider.NONE);
        Decision decision = decider.canAllocate(unassignedReplica, routingNodes.node("n0"), allocation);
        Assert.assertEquals(Decision.Type.NO, decision.type());
    }

    @Test
    public void testBalancerSpreadsMultipleIndicesEvenly() {
        List<DiscoveryNode> nodes = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            nodes.add(AllocationTestSupport.dataNode("n" + i, Map.of()));
        }
        ClusterState state = AllocationTestSupport.clusterStateWithIndex(nodes, "idx1", 3, 0);
        state = AllocationTestSupport.addIndex(state, "idx2", 3, 0);
        AllocationService service = AllocationTestSupport.defaultAllocationService();
        state = service.reroute(state, "initial", 0L);
        state = AllocationTestSupport.startInitializingShards(service, state, 0L);
        state = service.reroute(state, "again", 1L);
        state = AllocationTestSupport.startInitializingShards(service, state, 1L);

        Map<String, Integer> shardsPerNode = new HashMap<>();
        for (ShardRouting shard : state.getRoutingTable().allShards()) {
            shardsPerNode.merge(shard.currentNodeId(), 1, Integer::sum);
        }
        int max = shardsPerNode.values().stream().max(Integer::compareTo).orElse(0);
        int min = shardsPerNode.values().stream().min(Integer::compareTo).orElse(0);
        Assert.assertTrue(max - min <= 1, "expected balanced distribution across nodes, got " + shardsPerNode);
    }
}
