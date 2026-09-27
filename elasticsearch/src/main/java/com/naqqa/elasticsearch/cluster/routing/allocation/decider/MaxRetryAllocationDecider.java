package com.naqqa.elasticsearch.cluster.routing.allocation.decider;

import com.naqqa.elasticsearch.cluster.routing.RoutingNode;
import com.naqqa.elasticsearch.cluster.routing.ShardRouting;
import com.naqqa.elasticsearch.cluster.routing.allocation.AllocationDecider;
import com.naqqa.elasticsearch.cluster.routing.allocation.Decision;
import com.naqqa.elasticsearch.cluster.routing.allocation.RoutingAllocation;

public final class MaxRetryAllocationDecider implements AllocationDecider {

    public static final int DEFAULT_MAX_RETRIES = 5;

    @Override
    public Decision canAllocate(ShardRouting shardRouting, RoutingNode node, RoutingAllocation allocation) {
        if (shardRouting.unassignedInfo() == null) {
            return Decision.YES;
        }
        int maxRetries = allocation.indexSettings(shardRouting.getIndex())
            .getAsInt("index.allocation.max_retries", DEFAULT_MAX_RETRIES);
        int failures = shardRouting.unassignedInfo().getNumFailedAllocations();
        if (failures >= maxRetries) {
            return Decision.single(Decision.Type.NO, name(),
                "shard %s has exceeded the maximum number of allocation retries (%d/%d), manual retry required",
                shardRouting.shardId(), failures, maxRetries);
        }
        return Decision.YES;
    }
}
