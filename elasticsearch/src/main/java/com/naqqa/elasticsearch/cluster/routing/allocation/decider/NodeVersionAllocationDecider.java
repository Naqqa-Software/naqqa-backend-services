package com.naqqa.elasticsearch.cluster.routing.allocation.decider;

import com.naqqa.elasticsearch.cluster.node.DiscoveryNode;
import com.naqqa.elasticsearch.cluster.routing.RoutingNode;
import com.naqqa.elasticsearch.cluster.routing.ShardRouting;
import com.naqqa.elasticsearch.cluster.routing.allocation.AllocationDecider;
import com.naqqa.elasticsearch.cluster.routing.allocation.Decision;
import com.naqqa.elasticsearch.cluster.routing.allocation.RoutingAllocation;

public final class NodeVersionAllocationDecider implements AllocationDecider {

    @Override
    public Decision canAllocate(ShardRouting shardRouting, RoutingNode node, RoutingAllocation allocation) {
        if (shardRouting.primary()) {
            return Decision.YES;
        }
        DiscoveryNode targetNode = allocation.nodes().get(node.getNodeId());
        if (targetNode == null) {
            return Decision.YES;
        }
        for (RoutingNode candidate : allocation.routingNodes().getNodes().values()) {
            for (ShardRouting shard : candidate.getShards()) {
                if (shard.primary() && shard.getIndex().equals(shardRouting.getIndex())
                    && shard.getShardId() == shardRouting.getShardId() && shard.active()) {
                    DiscoveryNode primaryNode = allocation.nodes().get(candidate.getNodeId());
                    if (primaryNode != null && targetNode.getVersion() < primaryNode.getVersion()) {
                        return Decision.single(Decision.Type.NO, name(),
                            "node version [%d] is older than the primary's node version [%d] for shard %s",
                            targetNode.getVersion(), primaryNode.getVersion(), shardRouting.shardId());
                    }
                }
            }
        }
        return Decision.YES;
    }
}
