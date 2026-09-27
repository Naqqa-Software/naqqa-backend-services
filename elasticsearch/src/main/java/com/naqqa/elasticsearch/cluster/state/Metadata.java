package com.naqqa.elasticsearch.cluster.state;

import com.naqqa.elasticsearch.cluster.io.StreamUtils;
import com.naqqa.elasticsearch.cluster.io.Writeable;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class Metadata implements Writeable {

    public static final Metadata EMPTY = new Metadata("_na_", 0L, Settings.EMPTY, Settings.EMPTY,
        Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), CoordinationMetadata.ZERO, Map.of());

    private final String clusterUUID;
    private final long version;
    private final Settings persistentSettings;
    private final Settings transientSettings;
    private final Map<String, IndexMetadata> indices;
    private final Map<String, IndexTemplateMetadata> legacyTemplates;
    private final Map<String, ComponentTemplate> componentTemplates;
    private final Map<String, IndexTemplateV2> indexTemplates;
    private final Map<String, DataStreamMetadata> dataStreams;
    private final CoordinationMetadata coordinationMetadata;
    private final Map<String, Custom> customs;

    public Metadata(String clusterUUID, long version, Settings persistentSettings, Settings transientSettings,
                     Map<String, IndexMetadata> indices, Map<String, IndexTemplateMetadata> legacyTemplates,
                     Map<String, ComponentTemplate> componentTemplates, Map<String, IndexTemplateV2> indexTemplates,
                     Map<String, DataStreamMetadata> dataStreams, CoordinationMetadata coordinationMetadata,
                     Map<String, Custom> customs) {
        this.clusterUUID = clusterUUID;
        this.version = version;
        this.persistentSettings = persistentSettings;
        this.transientSettings = transientSettings;
        this.indices = Map.copyOf(indices);
        this.legacyTemplates = Map.copyOf(legacyTemplates);
        this.componentTemplates = Map.copyOf(componentTemplates);
        this.indexTemplates = Map.copyOf(indexTemplates);
        this.dataStreams = Map.copyOf(dataStreams);
        this.coordinationMetadata = coordinationMetadata;
        this.customs = Map.copyOf(customs);
    }

    public String getClusterUUID() {
        return clusterUUID;
    }

    public long getVersion() {
        return version;
    }

    public Settings getPersistentSettings() {
        return persistentSettings;
    }

    public Settings getTransientSettings() {
        return transientSettings;
    }

    public Settings settings() {
        return persistentSettings.merge(transientSettings);
    }

    public Map<String, IndexMetadata> getIndices() {
        return indices;
    }

    public IndexMetadata index(String name) {
        return indices.get(name);
    }

    public Map<String, IndexTemplateMetadata> getLegacyTemplates() {
        return legacyTemplates;
    }

    public Map<String, ComponentTemplate> getComponentTemplates() {
        return componentTemplates;
    }

    public Map<String, IndexTemplateV2> getIndexTemplates() {
        return indexTemplates;
    }

    public Map<String, DataStreamMetadata> getDataStreams() {
        return dataStreams;
    }

    public CoordinationMetadata coordinationMetadata() {
        return coordinationMetadata;
    }

    public Map<String, Custom> getCustoms() {
        return customs;
    }

    @SuppressWarnings("unchecked")
    public <T extends Custom> T custom(String name) {
        return (T) customs.get(name);
    }

    public Set<String> resolveIndicesForAlias(String alias) {
        Set<String> result = new java.util.LinkedHashSet<>();
        for (IndexMetadata imd : indices.values()) {
            if (imd.getAliases().containsKey(alias)) {
                result.add(imd.getIndex());
            }
        }
        return result;
    }

    public static Builder builder() {
        return new Builder(EMPTY);
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    @Override
    public void writeTo(DataOutput out) throws IOException {
        StreamUtils.writeString(out, clusterUUID);
        out.writeLong(version);
        persistentSettings.writeTo(out);
        transientSettings.writeTo(out);
        StreamUtils.writeVInt(out, indices.size());
        for (IndexMetadata imd : indices.values()) {
            imd.writeTo(out);
        }
        StreamUtils.writeVInt(out, legacyTemplates.size());
        for (IndexTemplateMetadata t : legacyTemplates.values()) {
            t.writeTo(out);
        }
        StreamUtils.writeVInt(out, componentTemplates.size());
        for (ComponentTemplate t : componentTemplates.values()) {
            t.writeTo(out);
        }
        StreamUtils.writeVInt(out, indexTemplates.size());
        for (IndexTemplateV2 t : indexTemplates.values()) {
            t.writeTo(out);
        }
        StreamUtils.writeVInt(out, dataStreams.size());
        for (DataStreamMetadata d : dataStreams.values()) {
            d.writeTo(out);
        }
        coordinationMetadata.writeTo(out);
        StreamUtils.writeVInt(out, customs.size());
    }

    public static Metadata readFrom(DataInput in) throws IOException {
        String clusterUUID = StreamUtils.readString(in);
        long version = in.readLong();
        Settings persistent = Settings.readFrom(in);
        Settings transientSettings = Settings.readFrom(in);
        int indexCount = StreamUtils.readVInt(in);
        Map<String, IndexMetadata> indices = new LinkedHashMap<>();
        for (int i = 0; i < indexCount; i++) {
            IndexMetadata imd = IndexMetadata.readFrom(in);
            indices.put(imd.getIndex(), imd);
        }
        int legacyCount = StreamUtils.readVInt(in);
        Map<String, IndexTemplateMetadata> legacy = new LinkedHashMap<>();
        for (int i = 0; i < legacyCount; i++) {
            IndexTemplateMetadata t = IndexTemplateMetadata.readFrom(in);
            legacy.put(t.getName(), t);
        }
        int componentCount = StreamUtils.readVInt(in);
        Map<String, ComponentTemplate> components = new LinkedHashMap<>();
        for (int i = 0; i < componentCount; i++) {
            ComponentTemplate t = ComponentTemplate.readFrom(in);
            components.put(t.getName(), t);
        }
        int templateCount = StreamUtils.readVInt(in);
        Map<String, IndexTemplateV2> templates = new LinkedHashMap<>();
        for (int i = 0; i < templateCount; i++) {
            IndexTemplateV2 t = IndexTemplateV2.readFrom(in);
            templates.put(t.getName(), t);
        }
        int dataStreamCount = StreamUtils.readVInt(in);
        Map<String, DataStreamMetadata> dataStreams = new LinkedHashMap<>();
        for (int i = 0; i < dataStreamCount; i++) {
            DataStreamMetadata d = DataStreamMetadata.readFrom(in);
            dataStreams.put(d.getName(), d);
        }
        CoordinationMetadata coordinationMetadata = CoordinationMetadata.readFrom(in);
        int customCount = StreamUtils.readVInt(in);
        return new Metadata(clusterUUID, version, persistent, transientSettings, indices, legacy, components,
            templates, dataStreams, coordinationMetadata, Map.of());
    }

    public static final class Builder {
        private String clusterUUID;
        private long version;
        private Settings persistentSettings;
        private Settings transientSettings;
        private Map<String, IndexMetadata> indices;
        private Map<String, IndexTemplateMetadata> legacyTemplates;
        private Map<String, ComponentTemplate> componentTemplates;
        private Map<String, IndexTemplateV2> indexTemplates;
        private Map<String, DataStreamMetadata> dataStreams;
        private CoordinationMetadata coordinationMetadata;
        private Map<String, Custom> customs;

        private Builder(Metadata source) {
            this.clusterUUID = source.clusterUUID;
            this.version = source.version;
            this.persistentSettings = source.persistentSettings;
            this.transientSettings = source.transientSettings;
            this.indices = new LinkedHashMap<>(source.indices);
            this.legacyTemplates = new LinkedHashMap<>(source.legacyTemplates);
            this.componentTemplates = new LinkedHashMap<>(source.componentTemplates);
            this.indexTemplates = new LinkedHashMap<>(source.indexTemplates);
            this.dataStreams = new LinkedHashMap<>(source.dataStreams);
            this.coordinationMetadata = source.coordinationMetadata;
            this.customs = new LinkedHashMap<>(source.customs);
        }

        public Builder clusterUUID(String clusterUUID) {
            this.clusterUUID = clusterUUID;
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

        public Builder persistentSettings(Settings settings) {
            this.persistentSettings = settings;
            return this;
        }

        public Builder transientSettings(Settings settings) {
            this.transientSettings = settings;
            return this;
        }

        public Builder put(IndexMetadata imd) {
            this.indices.put(imd.getIndex(), imd);
            return this;
        }

        public Builder remove(String index) {
            this.indices.remove(index);
            return this;
        }

        public Builder put(IndexTemplateMetadata template) {
            this.legacyTemplates.put(template.getName(), template);
            return this;
        }

        public Builder put(ComponentTemplate template) {
            this.componentTemplates.put(template.getName(), template);
            return this;
        }

        public Builder put(IndexTemplateV2 template) {
            this.indexTemplates.put(template.getName(), template);
            return this;
        }

        public Builder put(DataStreamMetadata dataStream) {
            this.dataStreams.put(dataStream.getName(), dataStream);
            return this;
        }

        public Builder coordinationMetadata(CoordinationMetadata coordinationMetadata) {
            this.coordinationMetadata = coordinationMetadata;
            return this;
        }

        public Builder putCustom(String name, Custom custom) {
            this.customs.put(name, custom);
            return this;
        }

        public Metadata build() {
            return new Metadata(clusterUUID, version, persistentSettings, transientSettings, indices,
                legacyTemplates, componentTemplates, indexTemplates, dataStreams, coordinationMetadata, customs);
        }
    }
}
