package com.naqqa.elasticsearch.cluster.state;

import com.naqqa.elasticsearch.cluster.io.StreamUtils;
import com.naqqa.elasticsearch.cluster.io.Writeable;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class ClusterBlocks implements Writeable {

    public static final ClusterBlocks EMPTY = new ClusterBlocks(Set.of(), Map.of());

    private final Set<ClusterBlock> global;
    private final Map<String, Set<ClusterBlock>> indices;

    private ClusterBlocks(Set<ClusterBlock> global, Map<String, Set<ClusterBlock>> indices) {
        this.global = Collections.unmodifiableSet(new LinkedHashSet<>(global));
        Map<String, Set<ClusterBlock>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Set<ClusterBlock>> entry : indices.entrySet()) {
            copy.put(entry.getKey(), Collections.unmodifiableSet(new LinkedHashSet<>(entry.getValue())));
        }
        this.indices = Collections.unmodifiableMap(copy);
    }

    public Set<ClusterBlock> global() {
        return global;
    }

    public Map<String, Set<ClusterBlock>> indices() {
        return indices;
    }

    public boolean hasGlobalBlock(ClusterBlockLevel level) {
        return global.stream().anyMatch(b -> b.contains(level));
    }

    public boolean hasIndexBlock(String index, ClusterBlockLevel level) {
        Set<ClusterBlock> blocks = indices.get(index);
        return blocks != null && blocks.stream().anyMatch(b -> b.contains(level));
    }

    public boolean disableStatePersistence() {
        return global.stream().anyMatch(ClusterBlock::isDisableStatePersistence);
    }

    public static Builder builder() {
        return new Builder(EMPTY);
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    @Override
    public void writeTo(DataOutput out) throws IOException {
        StreamUtils.writeVInt(out, global.size());
        for (ClusterBlock block : global) {
            block.writeTo(out);
        }
        StreamUtils.writeVInt(out, indices.size());
        for (Map.Entry<String, Set<ClusterBlock>> entry : indices.entrySet()) {
            StreamUtils.writeString(out, entry.getKey());
            StreamUtils.writeVInt(out, entry.getValue().size());
            for (ClusterBlock block : entry.getValue()) {
                block.writeTo(out);
            }
        }
    }

    public static ClusterBlocks readFrom(DataInput in) throws IOException {
        int globalCount = StreamUtils.readVInt(in);
        Set<ClusterBlock> global = new LinkedHashSet<>();
        for (int i = 0; i < globalCount; i++) {
            global.add(ClusterBlock.readFrom(in));
        }
        int indexCount = StreamUtils.readVInt(in);
        Map<String, Set<ClusterBlock>> indices = new LinkedHashMap<>();
        for (int i = 0; i < indexCount; i++) {
            String index = StreamUtils.readString(in);
            int blockCount = StreamUtils.readVInt(in);
            Set<ClusterBlock> blocks = new LinkedHashSet<>();
            for (int j = 0; j < blockCount; j++) {
                blocks.add(ClusterBlock.readFrom(in));
            }
            indices.put(index, blocks);
        }
        return new ClusterBlocks(global, indices);
    }

    public static final class Builder {
        private final Set<ClusterBlock> global;
        private final Map<String, Set<ClusterBlock>> indices;

        private Builder(ClusterBlocks source) {
            this.global = new LinkedHashSet<>(source.global);
            this.indices = new LinkedHashMap<>();
            for (Map.Entry<String, Set<ClusterBlock>> entry : source.indices.entrySet()) {
                this.indices.put(entry.getKey(), new LinkedHashSet<>(entry.getValue()));
            }
        }

        public Builder addGlobalBlock(ClusterBlock block) {
            global.add(block);
            return this;
        }

        public Builder removeGlobalBlock(int id) {
            global.removeIf(b -> b.getId() == id);
            return this;
        }

        public Builder addIndexBlock(String index, ClusterBlock block) {
            indices.computeIfAbsent(index, k -> new LinkedHashSet<>()).add(block);
            return this;
        }

        public Builder removeIndexBlocks(String index) {
            indices.remove(index);
            return this;
        }

        public ClusterBlocks build() {
            return new ClusterBlocks(global, indices);
        }
    }
}
