package com.naqqa.elasticsearch.cluster.routing;

import java.util.ArrayList;
import java.util.List;

public final class RoutingNode {

    private final String nodeId;
    private final List<ShardRouting> shards;

    public RoutingNode(String nodeId) {
        this.nodeId = nodeId;
        this.shards = new ArrayList<>();
    }

    public String getNodeId() {
        return nodeId;
    }

    public List<ShardRouting> getShards() {
        return shards;
    }

    public void add(ShardRouting shard) {
        shards.add(shard);
    }

    public void remove(ShardRouting shard) {
        shards.remove(shard);
    }

    public int size() {
        return shards.size();
    }

    public int numberOfShardsWithIndex(String index) {
        int count = 0;
        for (ShardRouting shard : shards) {
            if (shard.getIndex().equals(index)) {
                count++;
            }
        }
        return count;
    }

    public boolean hasShardWithId(String index, int shardId) {
        for (ShardRouting shard : shards) {
            if (shard.getIndex().equals(index) && shard.getShardId() == shardId) {
                return true;
            }
        }
        return false;
    }

    public int numberOfInitializingShards() {
        int count = 0;
        for (ShardRouting shard : shards) {
            if (shard.initializing()) {
                count++;
            }
        }
        return count;
    }

    public int numberOfRelocatingShards() {
        int count = 0;
        for (ShardRouting shard : shards) {
            if (shard.relocating()) {
                count++;
            }
        }
        return count;
    }
}
