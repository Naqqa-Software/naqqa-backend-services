package com.naqqa.elasticsearch.index.replication;

import com.naqqa.elasticsearch.cluster.routing.ShardId;

public interface ReplicaFailureListener {

    void onReplicaFailure(ShardId shardId, String allocationId, Exception cause);
}
