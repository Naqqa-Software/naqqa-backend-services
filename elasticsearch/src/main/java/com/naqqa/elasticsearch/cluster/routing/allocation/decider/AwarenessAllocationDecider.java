package com.naqqa.elasticsearch.cluster.routing.allocation.decider;

import com.naqqa.elasticsearch.cluster.node.DiscoveryNode;
import com.naqqa.elasticsearch.cluster.routing.RoutingNode;
import com.naqqa.elasticsearch.cluster.routing.ShardRouting;
import com.naqqa.elasticsearch.cluster.routing.allocation.AllocationDecider;
import com.naqqa.elasticsearch.cluster.routing.allocation.Decision;
import com.naqqa.elasticsearch.cluster.routing.allocation.RoutingAllocation;

import java.util.LinkedHashSet;
import java.util.Set;

public final class AwarenessAllocationDecider implements AllocationDecider {

    @Override
    public Decision canAllocate(ShardRouting shardRouting, RoutingNode node, RoutingAllocation allocation) {
        String attributesCsv = allocation.clusterSettings().get("cluster.routing.allocation.awareness.attributes");
        if (attributesCsv == null || attributesCsv.isBlank()) {
            return Decision.YES;
        }
        DiscoveryNode discoveryNode = allocation.nodes().get(node.getNodeId());
        if (discoveryNode == null) {
            return Decision.YES;
        }
        for (String attribute : attributesCsv.split(",")) {
            attribute = attribute.trim();
            String nodeValue = discoveryNode.getAttributes().get(attribute);
            if (nodeValue == null) {
                continue;
            }
            Set<String> zoneValues = forcedValues(allocation, attribute);
            if (zoneValues.isEmpty()) {
                zoneValues = observedValues(allocation, attribute);
            }
            int numZones = Math.max(1, zoneValues.size());
            int totalCopies = totalCopies(allocation, shardRouting.getIndex());
            int idealPerZone = (int) Math.ceil((double) totalCopies / numZones);
            int currentInZone = countInZone(allocation, shardRouting, attribute, nodeValue, node.getNodeId());
            if (currentInZone >= idealPerZone) {
                return Decision.single(Decision.Type.NO, name(),
                    "too many copies of shard %s already allocated to awareness zone [%s=%s]",
                    shardRouting.shardId(), attribute, nodeValue);
            }
        }
        return Decision.YES;
    }

    private Set<String> forcedValues(RoutingAllocation allocation, String attribute) {
        String csv = allocation.clusterSettings().get(
            "cluster.routing.allocation.awareness.force." + attribute + ".values");
        Set<String> result = new LinkedHashSet<>();
        if (csv != null) {
            for (String v : csv.split(",")) {
                result.add(v.trim());
            }
        }
        return result;
    }

    private Set<String> observedValues(RoutingAllocation allocation, String attribute) {
        Set<String> result = new LinkedHashSet<>();
        for (DiscoveryNode node : allocation.nodes().getNodes().values()) {
            String value = node.getAttributes().get(attribute);
            if (value != null) {
                result.add(value);
            }
        }
        return result;
    }

    private int totalCopies(RoutingAllocation allocation, String index) {
        var imd = allocation.metadata().index(index);
        return imd == null ? 1 : imd.getNumberOfShards() > 0
            ? imd.getNumberOfReplicas() + 1 : 1;
    }

    private int countInZone(RoutingAllocation allocation, ShardRouting shardRouting, String attribute,
                             String zoneValue, String excludeNodeId) {
        int count = 0;
        for (RoutingNode node : allocation.routingNodes().getNodes().values()) {
            if (node.getNodeId().equals(excludeNodeId)) {
                continue;
            }
            DiscoveryNode discoveryNode = allocation.nodes().get(node.getNodeId());
            if (discoveryNode == null || !zoneValue.equals(discoveryNode.getAttributes().get(attribute))) {
                continue;
            }
            for (ShardRouting shard : node.getShards()) {
                if (shard.getIndex().equals(shardRouting.getIndex()) && shard.getShardId() == shardRouting.getShardId()) {
                    count++;
                }
            }
        }
        return count;
    }
}
