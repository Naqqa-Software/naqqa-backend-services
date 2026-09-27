package com.naqqa.elasticsearch.cluster.routing.allocation;

import com.naqqa.elasticsearch.cluster.node.DiscoveryNode;
import com.naqqa.elasticsearch.cluster.support.AllocationTestSupport;
import com.naqqa.elasticsearch.cluster.state.ClusterState;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Map;

public final class ClusterHealthTest {

    @Test
    public void testHealthIsRedWhenPrimaryUnassigned() {
        List<DiscoveryNode> nodes = List.of(AllocationTestSupport.dataNode("n0", Map.of()));
        ClusterState state = AllocationTestSupport.clusterStateWithIndex(nodes, "idx", 1, 0);
        Map<String, Object> health = ClusterHealth.compute(state);
        Assert.assertEquals("red", health.get("status"));
    }

    @Test
    public void testHealthIsYellowWhenReplicaUnassigned() {
        List<DiscoveryNode> nodes = List.of(AllocationTestSupport.dataNode("n0", Map.of()));
        ClusterState state = AllocationTestSupport.clusterStateWithIndex(nodes, "idx", 1, 1);
        AllocationService service = AllocationTestSupport.defaultAllocationService();
        state = service.reroute(state, "initial", 0L);
        state = AllocationTestSupport.startInitializingShards(service, state, 0L);

        Map<String, Object> health = ClusterHealth.compute(state);
        Assert.assertEquals("yellow", health.get("status"));
        Assert.assertEquals(1, health.get("active_primary_shards"));
        Assert.assertEquals(1, health.get("unassigned_shards"));
    }

    @Test
    public void testHealthIsGreenWhenFullyAllocated() {
        List<DiscoveryNode> nodes = List.of(AllocationTestSupport.dataNode("n0", Map.of()), AllocationTestSupport.dataNode("n1", Map.of()));
        ClusterState state = AllocationTestSupport.clusterStateWithIndex(nodes, "idx", 1, 1);
        AllocationService service = AllocationTestSupport.defaultAllocationService();
        state = service.reroute(state, "initial", 0L);
        state = AllocationTestSupport.startInitializingShards(service, state, 0L);

        Map<String, Object> health = ClusterHealth.compute(state);
        Assert.assertEquals("green", health.get("status"));
        Assert.assertEquals(2, health.get("active_shards"));
        Assert.assertEquals(0, health.get("unassigned_shards"));
    }
}
