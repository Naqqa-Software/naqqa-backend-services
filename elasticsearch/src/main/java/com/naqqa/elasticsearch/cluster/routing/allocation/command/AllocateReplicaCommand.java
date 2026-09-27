package com.naqqa.elasticsearch.cluster.routing.allocation.command;

import com.naqqa.elasticsearch.cluster.routing.ShardRouting;
import com.naqqa.elasticsearch.cluster.routing.allocation.RoutingAllocation;

public record AllocateReplicaCommand(String index, int shardId, String nodeId) implements RerouteCommand {

    @Override
    public void execute(RoutingAllocation allocation) {
        ShardRouting shard = allocation.routingNodes().unassigned().stream()
            .filter(s -> s.getIndex().equals(index) && s.getShardId() == shardId && !s.primary())
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("no unassigned replica [" + index + "][" + shardId + "]"));
        long expectedSize = allocation.diskUsageProvider().getShardSizeBytes(index, shardId);
        allocation.routingNodes().initializeShard(shard, nodeId, expectedSize);
    }
}
