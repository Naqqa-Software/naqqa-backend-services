package com.naqqa.elasticsearch.cluster.routing.allocation;

import com.naqqa.elasticsearch.cluster.node.DiscoveryNode;
import com.naqqa.elasticsearch.cluster.routing.ShardRouting;
import com.naqqa.elasticsearch.cluster.support.AllocationTestSupport;
import com.naqqa.elasticsearch.cluster.state.ClusterState;
import com.naqqa.elasticsearch.cluster.state.Metadata;
import com.naqqa.elasticsearch.cluster.state.Settings;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class AwarenessAllocationTest {

    @Test
    public void testAwarenessSpreadsShardsAcrossZones() {
        List<DiscoveryNode> nodes = new ArrayList<>();
        nodes.add(AllocationTestSupport.dataNode("n0", Map.of("zone", "a")));
        nodes.add(AllocationTestSupport.dataNode("n1", Map.of("zone", "a")));
        nodes.add(AllocationTestSupport.dataNode("n2", Map.of("zone", "b")));
        nodes.add(AllocationTestSupport.dataNode("n3", Map.of("zone", "b")));

        ClusterState state = AllocationTestSupport.clusterStateWithIndex(nodes, "idx", 2, 1);
        Settings clusterSettings = Settings.builder().put("cluster.routing.allocation.awareness.attributes", "zone").build();
        Metadata metadata = state.getMetadata().toBuilder().persistentSettings(clusterSettings).build();
        state = state.builder().metadata(metadata).build();

        AllocationService service = AllocationTestSupport.defaultAllocationService();
        state = service.reroute(state, "initial", 0L);
        state = AllocationTestSupport.startInitializingShards(service, state, 0L);

        Map<String, Integer> zoneCounts = new HashMap<>();
        Map<String, String> nodeToZone = new HashMap<>();
        for (DiscoveryNode node : nodes) {
            nodeToZone.put(node.getId(), node.getAttributes().get("zone"));
        }
        for (ShardRouting shard : state.getRoutingTable().allShards()) {
            Assert.assertTrue(shard.started(), "expected shard to be allocated: " + shard);
            zoneCounts.merge(nodeToZone.get(shard.currentNodeId()), 1, Integer::sum);
        }
        Assert.assertEquals(2, zoneCounts.size(), "expected both zones to receive shard copies");
        Assert.assertEquals(2, (int) zoneCounts.get("a"));
        Assert.assertEquals(2, (int) zoneCounts.get("b"));
    }
}
