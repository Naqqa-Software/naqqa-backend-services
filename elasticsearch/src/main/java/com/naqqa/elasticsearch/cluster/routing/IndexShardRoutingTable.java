package com.naqqa.elasticsearch.cluster.routing;

import com.naqqa.elasticsearch.cluster.io.StreamUtils;
import com.naqqa.elasticsearch.cluster.io.Writeable;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class IndexShardRoutingTable implements Writeable {

    private final ShardId shardId;
    private final List<ShardRouting> shards;

    public IndexShardRoutingTable(ShardId shardId, List<ShardRouting> shards) {
        this.shardId = shardId;
        this.shards = List.copyOf(shards);
    }

    public ShardId getShardId() {
        return shardId;
    }

    public List<ShardRouting> getShards() {
        return shards;
    }

    public ShardRouting primaryShard() {
        for (ShardRouting shard : shards) {
            if (shard.primary()) {
                return shard;
            }
        }
        return null;
    }

    public List<ShardRouting> replicaShards() {
        List<ShardRouting> replicas = new ArrayList<>();
        for (ShardRouting shard : shards) {
            if (!shard.primary()) {
                replicas.add(shard);
            }
        }
        return replicas;
    }

    public boolean allShardsStarted() {
        for (ShardRouting shard : shards) {
            if (!shard.started()) {
                return false;
            }
        }
        return true;
    }

    public int activeShardCount() {
        int count = 0;
        for (ShardRouting shard : shards) {
            if (shard.active()) {
                count++;
            }
        }
        return count;
    }

    public IndexShardRoutingTable withShards(List<ShardRouting> newShards) {
        return new IndexShardRoutingTable(shardId, newShards);
    }

    @Override
    public void writeTo(DataOutput out) throws IOException {
        StreamUtils.writeString(out, shardId.index());
        StreamUtils.writeVInt(out, shardId.id());
        StreamUtils.writeVInt(out, shards.size());
        for (ShardRouting shard : shards) {
            shard.writeTo(out);
        }
    }

    public static IndexShardRoutingTable readFrom(DataInput in) throws IOException {
        String index = StreamUtils.readString(in);
        int id = StreamUtils.readVInt(in);
        int count = StreamUtils.readVInt(in);
        List<ShardRouting> shards = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            shards.add(ShardRouting.readFrom(in));
        }
        return new IndexShardRoutingTable(new ShardId(index, id), shards);
    }
}
