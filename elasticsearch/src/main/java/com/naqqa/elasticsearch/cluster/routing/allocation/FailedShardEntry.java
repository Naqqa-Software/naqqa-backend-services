package com.naqqa.elasticsearch.cluster.routing.allocation;

import com.naqqa.elasticsearch.cluster.routing.ShardRouting;

public record FailedShardEntry(ShardRouting shardRouting, String message) {
}
