package com.naqqa.elasticsearch.cluster.routing.allocation.decider;

import com.naqqa.elasticsearch.cluster.routing.RoutingNode;
import com.naqqa.elasticsearch.cluster.routing.ShardRouting;
import com.naqqa.elasticsearch.cluster.routing.allocation.AllocationDecider;
import com.naqqa.elasticsearch.cluster.routing.allocation.Decision;
import com.naqqa.elasticsearch.cluster.routing.allocation.RoutingAllocation;

public final class SameShardAllocationDecider implements AllocationDecider {

    @Override
    public Decision canAllocate(ShardRouting shardRouting, RoutingNode node, RoutingAllocation allocation) {
        for (ShardRouting existing : node.getShards()) {
            if (existing.getIndex().equals(shardRouting.getIndex()) && existing.getShardId() == shardRouting.getShardId()
                && !existing.equals(shardRouting)) {
                return Decision.single(Decision.Type.NO, name(),
                    "shard %s already has a copy [%s] on node [%s]", shardRouting.shardId(), existing, node.getNodeId());
            }
        }
        return Decision.YES;
    }

    @Override
    public Decision canRemain(ShardRouting shardRouting, RoutingNode node, RoutingAllocation allocation) {
        return canAllocate(shardRouting, node, allocation);
    }
}
