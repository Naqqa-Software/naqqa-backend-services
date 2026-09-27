package com.naqqa.elasticsearch.action.write;

import com.naqqa.elasticsearch.cluster.routing.ShardId;

@FunctionalInterface
public interface RelocationRetryListener {

    void onRelocationDetected(ShardId shardId, RuntimeException cause);
}
