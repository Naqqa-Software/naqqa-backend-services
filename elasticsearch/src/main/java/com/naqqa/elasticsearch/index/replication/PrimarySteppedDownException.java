package com.naqqa.elasticsearch.index.replication;

public final class PrimarySteppedDownException extends RuntimeException {

    public PrimarySteppedDownException(String shardId) {
        super("primary for shard " + shardId + " has stepped down and can no longer serve writes");
    }
}
