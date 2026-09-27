package com.naqqa.elasticsearch.cluster.routing.allocation.decider;

import com.naqqa.elasticsearch.cluster.node.DiscoveryNode;
import com.naqqa.elasticsearch.cluster.routing.RoutingNode;
import com.naqqa.elasticsearch.cluster.routing.ShardRouting;
import com.naqqa.elasticsearch.cluster.routing.allocation.AllocationDecider;
import com.naqqa.elasticsearch.cluster.routing.allocation.Decision;
import com.naqqa.elasticsearch.cluster.routing.allocation.RoutingAllocation;
import com.naqqa.elasticsearch.cluster.state.Settings;

import java.util.LinkedHashMap;
import java.util.Map;

public final class FilterAllocationDecider implements AllocationDecider {

    @Override
    public Decision canAllocate(ShardRouting shardRouting, RoutingNode node, RoutingAllocation allocation) {
        DiscoveryNode discoveryNode = allocation.nodes().get(node.getNodeId());
        if (discoveryNode == null) {
            return Decision.YES;
        }
        Map<String, String> require = filters(allocation, shardRouting.getIndex(), "require.");
        Map<String, String> include = filters(allocation, shardRouting.getIndex(), "include.");
        Map<String, String> exclude = filters(allocation, shardRouting.getIndex(), "exclude.");

        for (Map.Entry<String, String> entry : require.entrySet()) {
            if (!matchesAny(discoveryNode, entry.getKey(), entry.getValue())) {
                return Decision.single(Decision.Type.NO, name(),
                    "node does not match required filter [%s:%s]", entry.getKey(), entry.getValue());
            }
        }
        for (Map.Entry<String, String> entry : include.entrySet()) {
            if (!matchesAny(discoveryNode, entry.getKey(), entry.getValue())) {
                return Decision.single(Decision.Type.NO, name(),
                    "node does not match include filter [%s:%s]", entry.getKey(), entry.getValue());
            }
        }
        for (Map.Entry<String, String> entry : exclude.entrySet()) {
            if (matchesAny(discoveryNode, entry.getKey(), entry.getValue())) {
                return Decision.single(Decision.Type.NO, name(),
                    "node matches exclude filter [%s:%s]", entry.getKey(), entry.getValue());
            }
        }
        return Decision.YES;
    }

    @Override
    public Decision canRemain(ShardRouting shardRouting, RoutingNode node, RoutingAllocation allocation) {
        return canAllocate(shardRouting, node, allocation);
    }

    private Map<String, String> filters(RoutingAllocation allocation, String index, String type) {
        Map<String, String> merged = new LinkedHashMap<>();
        merged.putAll(allocation.clusterSettings().getByPrefix("cluster.routing.allocation." + type));
        merged.putAll(allocation.indexSettings(index).getByPrefix("index.routing.allocation." + type));
        return merged;
    }

    private boolean matchesAny(DiscoveryNode node, String attribute, String commaSeparatedValues) {
        String[] values = commaSeparatedValues.split(",");
        for (String value : values) {
            if (matchesOne(node, attribute, value.trim())) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesOne(DiscoveryNode node, String attribute, String value) {
        String actual = switch (attribute) {
            case "_name" -> node.getName();
            case "_ip", "_host" -> hostOf(node.getAddress());
            case "_id" -> node.getId();
            default -> node.getAttributes().get(attribute);
        };
        return actual != null && matchesWildcard(actual, value);
    }

    private String hostOf(String address) {
        int idx = address.indexOf(':');
        return idx == -1 ? address : address.substring(0, idx);
    }

    private boolean matchesWildcard(String actual, String pattern) {
        String regex = pattern.replace(".", "\\.").replace("*", ".*");
        return actual.matches(regex);
    }
}
