package com.naqqa.elasticsearch.cluster.routing.allocation;

import com.naqqa.elasticsearch.cluster.routing.RoutingNode;
import com.naqqa.elasticsearch.cluster.routing.ShardRouting;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AllocationDeciders {

    private final List<AllocationDecider> deciders;

    public AllocationDeciders(List<AllocationDecider> deciders) {
        this.deciders = List.copyOf(deciders);
    }

    public List<AllocationDecider> getDeciders() {
        return deciders;
    }

    public Decision canAllocate(ShardRouting shardRouting, RoutingNode node, RoutingAllocation allocation) {
        Decision.Multi multi = new Decision.Multi();
        for (AllocationDecider decider : deciders) {
            multi.add(decider.canAllocate(shardRouting, node, allocation));
        }
        return summarize(multi);
    }

    public Decision canRemain(ShardRouting shardRouting, RoutingNode node, RoutingAllocation allocation) {
        Decision.Multi multi = new Decision.Multi();
        for (AllocationDecider decider : deciders) {
            multi.add(decider.canRemain(shardRouting, node, allocation));
        }
        return summarize(multi);
    }

    public Decision canRebalance(ShardRouting shardRouting, RoutingAllocation allocation) {
        Decision.Multi multi = new Decision.Multi();
        for (AllocationDecider decider : deciders) {
            multi.add(decider.canRebalance(shardRouting, allocation));
        }
        return summarize(multi);
    }

    public Decision canForceAllocatePrimary(ShardRouting shardRouting, RoutingNode node, RoutingAllocation allocation) {
        Decision.Multi multi = new Decision.Multi();
        for (AllocationDecider decider : deciders) {
            multi.add(decider.canForceAllocatePrimary(shardRouting, node, allocation));
        }
        return summarize(multi);
    }

    public Map<String, Decision> explainAllocate(ShardRouting shardRouting, RoutingNode node, RoutingAllocation allocation) {
        Map<String, Decision> result = new LinkedHashMap<>();
        for (AllocationDecider decider : deciders) {
            result.put(decider.name(), decider.canAllocate(shardRouting, node, allocation));
        }
        return result;
    }

    private Decision summarize(Decision.Multi multi) {
        return switch (multi.type()) {
            case YES -> Decision.YES;
            case THROTTLE -> Decision.THROTTLE;
            case NO -> Decision.NO;
        };
    }
}
