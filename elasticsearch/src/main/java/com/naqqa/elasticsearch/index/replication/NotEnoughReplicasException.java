package com.naqqa.elasticsearch.index.replication;

public final class NotEnoughReplicasException extends RuntimeException {

    public NotEnoughReplicasException(String shardId, int required, int acked) {
        super("not enough active shard copies for shard " + shardId + ": required [" + required
            + "] replica acks but only got [" + acked + "]");
    }
}
