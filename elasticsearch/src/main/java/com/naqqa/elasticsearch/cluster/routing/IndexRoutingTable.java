package com.naqqa.elasticsearch.cluster.routing;

import com.naqqa.elasticsearch.cluster.io.StreamUtils;
import com.naqqa.elasticsearch.cluster.io.Writeable;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public final class IndexRoutingTable implements Writeable {

    private final String index;
    private final Map<Integer, IndexShardRoutingTable> shards;

    public IndexRoutingTable(String index, Map<Integer, IndexShardRoutingTable> shards) {
        this.index = index;
        this.shards = Map.copyOf(shards);
    }

    public String getIndex() {
        return index;
    }

    public Map<Integer, IndexShardRoutingTable> getShards() {
        return shards;
    }

    public IndexShardRoutingTable shard(int shardId) {
        return shards.get(shardId);
    }

    public boolean allShardsActive() {
        for (IndexShardRoutingTable table : shards.values()) {
            for (ShardRouting shard : table.getShards()) {
                if (!shard.active()) {
                    return false;
                }
            }
        }
        return true;
    }

    public static Builder builder(String index) {
        return new Builder(index);
    }

    public Builder builder() {
        return new Builder(this);
    }

    @Override
    public void writeTo(DataOutput out) throws IOException {
        StreamUtils.writeString(out, index);
        StreamUtils.writeVInt(out, shards.size());
        for (IndexShardRoutingTable table : shards.values()) {
            table.writeTo(out);
        }
    }

    public static IndexRoutingTable readFrom(DataInput in) throws IOException {
        String index = StreamUtils.readString(in);
        int count = StreamUtils.readVInt(in);
        Map<Integer, IndexShardRoutingTable> shards = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            IndexShardRoutingTable table = IndexShardRoutingTable.readFrom(in);
            shards.put(table.getShardId().id(), table);
        }
        return new IndexRoutingTable(index, shards);
    }

    public static final class Builder {
        private final String index;
        private final Map<Integer, IndexShardRoutingTable> shards;

        private Builder(String index) {
            this.index = index;
            this.shards = new LinkedHashMap<>();
        }

        private Builder(IndexRoutingTable source) {
            this.index = source.index;
            this.shards = new LinkedHashMap<>(source.shards);
        }

        public Builder initializeAsNew(String index, int numShards, int numReplicas, long nowMillis) {
            for (int i = 0; i < numShards; i++) {
                java.util.List<ShardRouting> shardList = new java.util.ArrayList<>();
                shardList.add(ShardRouting.unassigned(index, i, true,
                    UnassignedInfo.of(UnassignedInfo.Reason.INDEX_CREATED, "index created", nowMillis)));
                for (int r = 0; r < numReplicas; r++) {
                    shardList.add(ShardRouting.unassigned(index, i, false,
                        UnassignedInfo.of(UnassignedInfo.Reason.INDEX_CREATED, "index created", nowMillis)));
                }
                shards.put(i, new IndexShardRoutingTable(new ShardId(index, i), shardList));
            }
            return this;
        }

        public Builder putShardTable(IndexShardRoutingTable table) {
            shards.put(table.getShardId().id(), table);
            return this;
        }

        public IndexRoutingTable build() {
            return new IndexRoutingTable(index, shards);
        }
    }
}
