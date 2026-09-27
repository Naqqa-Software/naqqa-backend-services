package com.naqqa.elasticsearch.cluster.routing.allocation;

import com.naqqa.elasticsearch.cluster.routing.RoutingNode;
import com.naqqa.elasticsearch.cluster.routing.ShardRouting;

public interface AllocationDecider {

    default Decision canAllocate(ShardRouting shardRouting, RoutingNode node, RoutingAllocation allocation) {
        return Decision.YES;
    }

    default Decision canRemain(ShardRouting shardRouting, RoutingNode node, RoutingAllocation allocation) {
        return Decision.YES;
    }

    default Decision canRebalance(ShardRouting shardRouting, RoutingAllocation allocation) {
        return Decision.YES;
    }

    default Decision canForceAllocatePrimary(ShardRouting shardRouting, RoutingNode node, RoutingAllocation allocation) {
        return Decision.YES;
    }

    default String name() {
        return getClass().getSimpleName();
    }
}
