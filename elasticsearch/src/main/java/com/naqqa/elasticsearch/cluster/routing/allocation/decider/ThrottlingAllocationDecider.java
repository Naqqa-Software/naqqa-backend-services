package com.naqqa.elasticsearch.cluster.routing.allocation.decider;

import com.naqqa.elasticsearch.cluster.routing.RoutingNode;
import com.naqqa.elasticsearch.cluster.routing.ShardRouting;
import com.naqqa.elasticsearch.cluster.routing.allocation.AllocationDecider;
import com.naqqa.elasticsearch.cluster.routing.allocation.Decision;
import com.naqqa.elasticsearch.cluster.routing.allocation.RoutingAllocation;

public final class ThrottlingAllocationDecider implements AllocationDecider {

    public static final int DEFAULT_CONCURRENT_RECOVERIES = 2;

    @Override
    public Decision canAllocate(ShardRouting shardRouting, RoutingNode node, RoutingAllocation allocation) {
        int limit = allocation.clusterSettings().getAsInt(
            "cluster.routing.allocation.node_concurrent_incoming_recoveries",
            allocation.clusterSettings().getAsInt("cluster.routing.allocation.node_concurrent_recoveries",
                DEFAULT_CONCURRENT_RECOVERIES));
        int currentlyInitializing = node.numberOfInitializingShards();
        if (currentlyInitializing >= limit) {
            return Decision.single(Decision.Type.THROTTLE, name(),
                "node [%s] has reached the limit of concurrent incoming shard recoveries [%d]",
                node.getNodeId(), limit);
        }
        return Decision.YES;
    }

    @Override
    public Decision canRebalance(ShardRouting shardRouting, RoutingAllocation allocation) {
        int limit = allocation.clusterSettings().getAsInt(
            "cluster.routing.allocation.node_concurrent_outgoing_recoveries",
            allocation.clusterSettings().getAsInt("cluster.routing.allocation.node_concurrent_recoveries",
                DEFAULT_CONCURRENT_RECOVERIES));
        RoutingNode source = allocation.routingNodes().node(shardRouting.currentNodeId());
        if (source != null && source.numberOfRelocatingShards() >= limit) {
            return Decision.single(Decision.Type.THROTTLE, name(),
                "node [%s] has reached the limit of concurrent outgoing shard recoveries [%d]",
                source.getNodeId(), limit);
        }
        return Decision.YES;
    }
}
