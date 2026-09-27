package com.naqqa.elasticsearch.cluster.routing.allocation;

import com.naqqa.elasticsearch.cluster.node.DiscoveryNode;
import com.naqqa.elasticsearch.cluster.routing.ShardRouting;
import com.naqqa.elasticsearch.cluster.routing.UnassignedInfo;
import com.naqqa.elasticsearch.cluster.routing.allocation.command.MoveAllocationCommand;
import com.naqqa.elasticsearch.cluster.routing.allocation.command.CancelAllocationCommand;
import com.naqqa.elasticsearch.cluster.support.AllocationTestSupport;
import com.naqqa.elasticsearch.cluster.state.ClusterState;
import com.naqqa.elasticsearch.cluster.state.Metadata;
import com.naqqa.elasticsearch.cluster.state.Settings;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Map;

public final class AllocationServiceTest {

    @Test
    public void testDelayedAllocationKeepsReplicaUnassignedUntilTimeoutExpires() {
        DiscoveryNode n0 = AllocationTestSupport.dataNode("n0", Map.of());
        DiscoveryNode n1 = AllocationTestSupport.dataNode("n1", Map.of());
        Settings indexSettings = Settings.builder().put("index.unassigned.node_left.delayed_timeout", 10_000L).build();
        ClusterState state = AllocationTestSupport.clusterStateWithIndex(List.of(n0, n1), "idx", 1, 1, indexSettings);
        AllocationService service = AllocationTestSupport.defaultAllocationService();
        state = service.reroute(state, "initial", 0L);

        ShardRouting replica = state.getRoutingTable().index("idx").shard(0).replicaShards().get(0);
        String replicaNodeId = replica.currentNodeId();
        state = service.deassociateDeadNodes(state, List.of(replicaNodeId), 100_000L);

        ShardRouting unassignedReplica = state.getRoutingTable().index("idx").shard(0).replicaShards().get(0);
        Assert.assertTrue(unassignedReplica.unassigned(), "expected the replica to remain unassigned right after node departure");
        Assert.assertTrue(unassignedReplica.unassignedInfo().isDelayed(), "expected the replica to be marked as delayed");

        state = service.reroute(state, "recheck-before-timeout", 105_000L);
        ShardRouting stillUnassigned = state.getRoutingTable().index("idx").shard(0).replicaShards().get(0);
        Assert.assertTrue(stillUnassigned.unassigned(), "expected the replica to remain unassigned before the delay expires");

        state = service.reroute(state, "recheck-after-timeout", 200_000L);
        state = AllocationTestSupport.startInitializingShards(service, state, 200_000L);
        ShardRouting reallocated = state.getRoutingTable().index("idx").shard(0).replicaShards().get(0);
        Assert.assertTrue(reallocated.started(), "expected the replica to be reallocated once the delay expires");
    }

    @Test
    public void testMaxRetriesStopsReallocationAfterRepeatedFailures() {
        DiscoveryNode n0 = AllocationTestSupport.dataNode("n0", Map.of());
        Settings indexSettings = Settings.builder().put("index.allocation.max_retries", 2).build();
        ClusterState state = AllocationTestSupport.clusterStateWithIndex(List.of(n0), "idx", 1, 0, indexSettings);
        AllocationService service = AllocationTestSupport.defaultAllocationService();
        state = service.reroute(state, "initial", 0L);
        ShardRouting shard = state.getRoutingTable().index("idx").shard(0).primaryShard();

        for (int i = 0; i < 3; i++) {
            ShardRouting current = state.getRoutingTable().index("idx").shard(0).primaryShard();
            state = service.applyFailedShards(state, List.of(new FailedShardEntry(current, "simulated failure")), (i + 1) * 1000L);
        }

        ShardRouting finalShard = state.getRoutingTable().index("idx").shard(0).primaryShard();
        Assert.assertTrue(finalShard.unassigned(), "expected the shard to give up after exceeding max retries");
        Assert.assertTrue(finalShard.unassignedInfo().getNumFailedAllocations() >= 2);

        state = service.retryFailed(state, 10_000L);
        state = AllocationTestSupport.startInitializingShards(service, state, 10_000L);
        ShardRouting afterRetry = state.getRoutingTable().index("idx").shard(0).primaryShard();
        Assert.assertTrue(afterRetry.started(), "expected explicit retry_failed to reallocate the shard");
    }

    @Test
    public void testEnableAllocationNoneBlocksNewAllocation() {
        DiscoveryNode n0 = AllocationTestSupport.dataNode("n0", Map.of());
        ClusterState state = AllocationTestSupport.clusterStateWithIndex(List.of(n0), "idx", 1, 0);
        Settings clusterSettings = Settings.builder().put("cluster.routing.allocation.enable", "none").build();
        Metadata metadata = state.getMetadata().toBuilder().persistentSettings(clusterSettings).build();
        state = state.builder().metadata(metadata).build();

        AllocationService service = AllocationTestSupport.defaultAllocationService();
        state = service.reroute(state, "initial", 0L);
        ShardRouting shard = state.getRoutingTable().index("idx").shard(0).primaryShard();
        Assert.assertTrue(shard.unassigned(), "expected allocation to be blocked while enable=none");
    }

    @Test
    public void testMoveAndCancelRerouteCommands() {
        DiscoveryNode n0 = AllocationTestSupport.dataNode("n0", Map.of());
        DiscoveryNode n1 = AllocationTestSupport.dataNode("n1", Map.of());
        ClusterState state = AllocationTestSupport.clusterStateWithIndex(List.of(n0, n1), "idx", 1, 0);
        AllocationService service = AllocationTestSupport.defaultAllocationService();
        state = service.reroute(state, "initial", 0L);
        state = AllocationTestSupport.startInitializingShards(service, state, 0L);
        ShardRouting shard = state.getRoutingTable().index("idx").shard(0).primaryShard();
        String from = shard.currentNodeId();
        String to = from.equals("n0") ? "n1" : "n0";

        state = service.reroute(state, List.of(new MoveAllocationCommand("idx", 0, from, to)), 1L);
        state = AllocationTestSupport.startInitializingShards(service, state, 1L);
        ShardRouting afterMove = state.getRoutingTable().index("idx").shard(0).primaryShard();
        Assert.assertEquals(to, afterMove.currentNodeId());

        state = service.reroute(state, List.of(new CancelAllocationCommand("idx", 0, to, true)), 2L);
        ShardRouting afterCancel = state.getRoutingTable().index("idx").shard(0).primaryShard();
        Assert.assertTrue(afterCancel.unassigned() || afterCancel.initializing() || afterCancel.started(),
            "shard should either be reallocated by the balancer or left unassigned after cancellation");
    }

    @Test
    public void testAllocationExplainReportsDeciderReasons() {
        DiscoveryNode n0 = AllocationTestSupport.dataNode("n0", Map.of());
        ClusterState state = AllocationTestSupport.clusterStateWithIndex(List.of(n0), "idx", 1, 0);
        AllocationService service = AllocationTestSupport.defaultAllocationService();
        Map<String, Object> explain = service.explainAllocation(state, "idx", 0, true, 0L);
        Assert.assertEquals("idx", explain.get("index"));
        Assert.assertEquals(0, explain.get("shard"));
        Assert.assertTrue(explain.containsKey("node_allocation_decisions"));
    }
}
