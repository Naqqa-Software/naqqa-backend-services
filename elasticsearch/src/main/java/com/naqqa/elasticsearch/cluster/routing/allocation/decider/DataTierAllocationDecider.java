package com.naqqa.elasticsearch.cluster.routing.allocation.decider;

import com.naqqa.elasticsearch.cluster.node.DiscoveryNode;
import com.naqqa.elasticsearch.cluster.node.DiscoveryNodeRole;
import com.naqqa.elasticsearch.cluster.routing.RoutingNode;
import com.naqqa.elasticsearch.cluster.routing.ShardRouting;
import com.naqqa.elasticsearch.cluster.routing.allocation.AllocationDecider;
import com.naqqa.elasticsearch.cluster.routing.allocation.Decision;
import com.naqqa.elasticsearch.cluster.routing.allocation.RoutingAllocation;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class DataTierAllocationDecider implements AllocationDecider {

    public static final String TIER_PREFERENCE_KEY = "index.routing.allocation.include._tier_preference";

    @Override
    public Decision canAllocate(ShardRouting shardRouting, RoutingNode node, RoutingAllocation allocation) {
        String preference = allocation.indexSettings(shardRouting.getIndex()).get(TIER_PREFERENCE_KEY);
        if (preference == null || preference.isBlank()) {
            return Decision.YES;
        }
        DiscoveryNode discoveryNode = allocation.nodes().get(node.getNodeId());
        if (discoveryNode == null) {
            return Decision.YES;
        }
        String preferredTier = resolvePreferredTier(allocation, preference);
        if (preferredTier == null) {
            return Decision.YES;
        }
        DiscoveryNodeRole role = DiscoveryNodeRole.fromRoleName(preferredTier);
        if (!discoveryNode.hasRole(role)) {
            return Decision.single(Decision.Type.NO, name(),
                "node does not have the preferred data tier role [%s] for shard %s", preferredTier, shardRouting.shardId());
        }
        return Decision.YES;
    }

    private String resolvePreferredTier(RoutingAllocation allocation, String preference) {
        Set<String> availableTiers = new LinkedHashSet<>();
        for (DiscoveryNode node : allocation.nodes().getNodes().values()) {
            for (DiscoveryNodeRole role : node.getRoles()) {
                if (role.isDataRole()) {
                    availableTiers.add(role.roleName());
                }
            }
        }
        for (String tier : preference.split(",")) {
            String trimmed = tier.trim().toLowerCase(Locale.ROOT);
            if (availableTiers.contains(trimmed)) {
                return trimmed;
            }
        }
        return null;
    }
}
