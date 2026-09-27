package com.naqqa.elasticsearch.cluster.routing.allocation.command;

import com.naqqa.elasticsearch.cluster.routing.RoutingNode;
import com.naqqa.elasticsearch.cluster.routing.ShardRouting;
import com.naqqa.elasticsearch.cluster.routing.UnassignedInfo;
import com.naqqa.elasticsearch.cluster.routing.allocation.RoutingAllocation;

public record CancelAllocationCommand(String index, int shardId, String nodeId, boolean allowPrimary)
    implements RerouteCommand {

    @Override
    public void execute(RoutingAllocation allocation) {
        RoutingNode node = allocation.routingNodes().node(nodeId);
        if (node == null) {
            throw new IllegalArgumentException("no such node [" + nodeId + "]");
        }
        ShardRouting shard = node.getShards().stream()
            .filter(s -> s.getIndex().equals(index) && s.getShardId() == shardId)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("no shard [" + index + "][" + shardId
                + "] on node [" + nodeId + "]"));
        if (shard.primary() && !allowPrimary) {
            throw new IllegalArgumentException("shard [" + index + "][" + shardId + "] is primary, "
                + "set allow_primary to force cancellation");
        }
        allocation.routingNodes().failShard(shard,
            UnassignedInfo.of(UnassignedInfo.Reason.REROUTE_CANCELLED, "cancelled by reroute command",
                allocation.nowMillis()));
    }
}
