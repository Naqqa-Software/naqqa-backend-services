package com.naqqa.elasticsearch.cluster.state;

import com.naqqa.elasticsearch.cluster.io.GenericValueIO;
import com.naqqa.elasticsearch.cluster.io.StreamUtils;
import com.naqqa.elasticsearch.cluster.io.Writeable;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class IndexMetadata implements Writeable {

    public enum State {
        OPEN, CLOSE
    }

    private final String index;
    private final String indexUUID;
    private final long version;
    private final State state;
    private final Settings settings;
    private final Map<String, Object> mappings;
    private final Map<String, AliasMetadata> aliases;
    private final Map<Integer, Set<String>> inSyncAllocationIds;
    private final Map<Integer, Long> primaryTerms;

    public IndexMetadata(String index, String indexUUID, long version, State state, Settings settings,
                          Map<String, Object> mappings, Map<String, AliasMetadata> aliases,
                          Map<Integer, Set<String>> inSyncAllocationIds, Map<Integer, Long> primaryTerms) {
        this.index = index;
        this.indexUUID = indexUUID;
        this.version = version;
        this.state = state;
        this.settings = settings;
        this.mappings = Map.copyOf(mappings);
        this.aliases = Map.copyOf(aliases);
        this.inSyncAllocationIds = Map.copyOf(inSyncAllocationIds);
        this.primaryTerms = Map.copyOf(primaryTerms);
    }

    public String getIndex() {
        return index;
    }

    public String getIndexUUID() {
        return indexUUID;
    }

    public long getVersion() {
        return version;
    }

    public State getState() {
        return state;
    }

    public Settings getSettings() {
        return settings;
    }

    public Map<String, Object> getMappings() {
        return mappings;
    }

    public Map<String, AliasMetadata> getAliases() {
        return aliases;
    }

    public int getNumberOfShards() {
        return settings.getAsInt("index.number_of_shards", 1);
    }

    public int getNumberOfReplicas() {
        return settings.getAsInt("index.number_of_replicas", 1);
    }

    public Set<String> inSyncAllocationIds(int shardId) {
        return inSyncAllocationIds.getOrDefault(shardId, Set.of());
    }

    public Map<Integer, Set<String>> getInSyncAllocationIds() {
        return inSyncAllocationIds;
    }

    public long primaryTerm(int shardId) {
        return primaryTerms.getOrDefault(shardId, 1L);
    }

    public Map<Integer, Long> getPrimaryTerms() {
        return primaryTerms;
    }

    public Builder builder() {
        return new Builder(this);
    }

    public static Builder builder(String index) {
        return new Builder(index);
    }

    @Override
    public void writeTo(DataOutput out) throws IOException {
        StreamUtils.writeString(out, index);
        StreamUtils.writeString(out, indexUUID);
        out.writeLong(version);
        StreamUtils.writeString(out, state.name());
        settings.writeTo(out);
        GenericValueIO.writeMap(out, mappings);
        StreamUtils.writeVInt(out, aliases.size());
        for (AliasMetadata alias : aliases.values()) {
            alias.writeTo(out);
        }
        StreamUtils.writeVInt(out, inSyncAllocationIds.size());
        for (Map.Entry<Integer, Set<String>> entry : inSyncAllocationIds.entrySet()) {
            StreamUtils.writeVInt(out, entry.getKey());
            StreamUtils.writeVInt(out, entry.getValue().size());
            for (String id : entry.getValue()) {
                StreamUtils.writeString(out, id);
            }
        }
        StreamUtils.writeVInt(out, primaryTerms.size());
        for (Map.Entry<Integer, Long> entry : primaryTerms.entrySet()) {
            StreamUtils.writeVInt(out, entry.getKey());
            out.writeLong(entry.getValue());
        }
    }

    public static IndexMetadata readFrom(DataInput in) throws IOException {
        String index = StreamUtils.readString(in);
        String uuid = StreamUtils.readString(in);
        long version = in.readLong();
        State state = State.valueOf(StreamUtils.readString(in));
        Settings settings = Settings.readFrom(in);
        Map<String, Object> mappings = GenericValueIO.readMap(in);
        int aliasCount = StreamUtils.readVInt(in);
        Map<String, AliasMetadata> aliases = new LinkedHashMap<>();
        for (int i = 0; i < aliasCount; i++) {
            AliasMetadata alias = AliasMetadata.readFrom(in);
            aliases.put(alias.getAlias(), alias);
        }
        int inSyncCount = StreamUtils.readVInt(in);
        Map<Integer, Set<String>> inSync = new LinkedHashMap<>();
        for (int i = 0; i < inSyncCount; i++) {
            int shard = StreamUtils.readVInt(in);
            int idCount = StreamUtils.readVInt(in);
            Set<String> ids = new LinkedHashSet<>();
            for (int j = 0; j < idCount; j++) {
                ids.add(StreamUtils.readString(in));
            }
            inSync.put(shard, ids);
        }
        int termCount = StreamUtils.readVInt(in);
        Map<Integer, Long> terms = new LinkedHashMap<>();
        for (int i = 0; i < termCount; i++) {
            terms.put(StreamUtils.readVInt(in), in.readLong());
        }
        return new IndexMetadata(index, uuid, version, state, settings, mappings, aliases, inSync, terms);
    }

    public static final class Builder {
        private final String index;
        private String indexUUID;
        private long version;
        private State state = State.OPEN;
        private Settings settings = Settings.EMPTY;
        private Map<String, Object> mappings = new LinkedHashMap<>();
        private Map<String, AliasMetadata> aliases = new LinkedHashMap<>();
        private Map<Integer, Set<String>> inSyncAllocationIds = new LinkedHashMap<>();
        private Map<Integer, Long> primaryTerms = new LinkedHashMap<>();

        private Builder(String index) {
            this.index = index;
            this.indexUUID = com.naqqa.elasticsearch.cluster.node.NodeIdentity.generate();
        }

        private Builder(IndexMetadata source) {
            this.index = source.index;
            this.indexUUID = source.indexUUID;
            this.version = source.version;
            this.state = source.state;
            this.settings = source.settings;
            this.mappings = new LinkedHashMap<>(source.mappings);
            this.aliases = new LinkedHashMap<>(source.aliases);
            this.inSyncAllocationIds = new LinkedHashMap<>(source.inSyncAllocationIds);
            this.primaryTerms = new LinkedHashMap<>(source.primaryTerms);
        }

        public Builder settings(Settings settings) {
            this.settings = settings;
            return this;
        }

        public Builder state(State state) {
            this.state = state;
            return this;
        }

        public Builder version(long version) {
            this.version = version;
            return this;
        }

        public Builder incrementVersion() {
            this.version++;
            return this;
        }

        public Builder mappings(Map<String, Object> mappings) {
            this.mappings = new LinkedHashMap<>(mappings);
            return this;
        }

        public Builder putAlias(AliasMetadata alias) {
            this.aliases.put(alias.getAlias(), alias);
            return this;
        }

        public Builder putInSyncAllocationIds(int shardId, Set<String> ids) {
            this.inSyncAllocationIds.put(shardId, Set.copyOf(ids));
            return this;
        }

        public Builder primaryTerm(int shardId, long term) {
            this.primaryTerms.put(shardId, term);
            return this;
        }

        public IndexMetadata build() {
            Map<Integer, Long> terms = new LinkedHashMap<>(primaryTerms);
            int numShards = settings.getAsInt("index.number_of_shards", 1);
            for (int i = 0; i < numShards; i++) {
                terms.putIfAbsent(i, 1L);
            }
            return new IndexMetadata(index, indexUUID, version, state, settings, mappings, aliases,
                inSyncAllocationIds, terms);
        }
    }
}
