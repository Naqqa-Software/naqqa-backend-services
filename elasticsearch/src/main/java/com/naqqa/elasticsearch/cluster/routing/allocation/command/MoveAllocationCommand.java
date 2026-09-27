package com.naqqa.elasticsearch.cluster.routing.allocation.command;

import com.naqqa.elasticsearch.cluster.routing.RoutingNode;
import com.naqqa.elasticsearch.cluster.routing.ShardRouting;
import com.naqqa.elasticsearch.cluster.routing.allocation.RoutingAllocation;

public record MoveAllocationCommand(String index, int shardId, String fromNodeId, String toNodeId)
    implements RerouteCommand {

    @Override
    public void execute(RoutingAllocation allocation) {
        RoutingNode from = allocation.routingNodes().node(fromNodeId);
        if (from == null) {
            throw new IllegalArgumentException("no such node [" + fromNodeId + "]");
        }
        ShardRouting shard = from.getShards().stream()
            .filter(s -> s.getIndex().equals(index) && s.getShardId() == shardId && s.started())
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("no started shard [" + index + "][" + shardId
                + "] on node [" + fromNodeId + "]"));
        long expectedSize = allocation.diskUsageProvider().getShardSizeBytes(index, shardId);
        allocation.routingNodes().relocateShard(shard, toNodeId, expectedSize);
    }
}
