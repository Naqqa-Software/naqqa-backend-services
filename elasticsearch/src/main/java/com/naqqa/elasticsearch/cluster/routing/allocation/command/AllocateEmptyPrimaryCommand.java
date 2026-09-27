package com.naqqa.elasticsearch.cluster.routing.allocation.command;

import com.naqqa.elasticsearch.cluster.routing.ShardRouting;
import com.naqqa.elasticsearch.cluster.routing.allocation.RoutingAllocation;

public record AllocateEmptyPrimaryCommand(String index, int shardId, String nodeId, boolean acceptDataLoss)
    implements RerouteCommand {

    @Override
    public void execute(RoutingAllocation allocation) {
        if (!acceptDataLoss) {
            throw new IllegalArgumentException("accept_data_loss must be true to force-allocate an empty primary");
        }
        ShardRouting shard = allocation.routingNodes().unassigned().stream()
            .filter(s -> s.getIndex().equals(index) && s.getShardId() == shardId && s.primary())
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("no unassigned primary [" + index + "][" + shardId + "]"));
        allocation.routingNodes().initializeShard(shard, nodeId, 0L);
    }
}
