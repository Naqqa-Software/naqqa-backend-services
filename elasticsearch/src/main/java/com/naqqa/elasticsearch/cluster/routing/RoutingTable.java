package com.naqqa.elasticsearch.cluster.routing;

import com.naqqa.elasticsearch.cluster.io.StreamUtils;
import com.naqqa.elasticsearch.cluster.io.Writeable;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RoutingTable implements Writeable {

    public static final RoutingTable EMPTY = new RoutingTable(0L, Map.of());

    private final long version;
    private final Map<String, IndexRoutingTable> indices;

    public RoutingTable(long version, Map<String, IndexRoutingTable> indices) {
        this.version = version;
        this.indices = Map.copyOf(indices);
    }

    public long getVersion() {
        return version;
    }

    public Map<String, IndexRoutingTable> getIndicesRouting() {
        return indices;
    }

    public IndexRoutingTable index(String index) {
        return indices.get(index);
    }

    public List<ShardRouting> allShards() {
        List<ShardRouting> all = new ArrayList<>();
        for (IndexRoutingTable indexTable : indices.values()) {
            for (IndexShardRoutingTable shardTable : indexTable.getShards().values()) {
                all.addAll(shardTable.getShards());
            }
        }
        return all;
    }

    public static Builder builder() {
        return new Builder(EMPTY);
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    @Override
    public void writeTo(DataOutput out) throws IOException {
        out.writeLong(version);
        StreamUtils.writeVInt(out, indices.size());
        for (IndexRoutingTable table : indices.values()) {
            table.writeTo(out);
        }
    }

    public static RoutingTable readFrom(DataInput in) throws IOException {
        long version = in.readLong();
        int count = StreamUtils.readVInt(in);
        Map<String, IndexRoutingTable> indices = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            IndexRoutingTable table = IndexRoutingTable.readFrom(in);
            indices.put(table.getIndex(), table);
        }
        return new RoutingTable(version, indices);
    }

    public static final class Builder {
        private long version;
        private final Map<String, IndexRoutingTable> indices;

        private Builder(RoutingTable source) {
            this.version = source.version;
            this.indices = new LinkedHashMap<>(source.indices);
        }

        public Builder incrementVersion() {
            version++;
            return this;
        }

        public Builder add(IndexRoutingTable table) {
            indices.put(table.getIndex(), table);
            return this;
        }

        public Builder remove(String index) {
            indices.remove(index);
            return this;
        }

        public RoutingTable build() {
            return new RoutingTable(version, indices);
        }
    }
}
