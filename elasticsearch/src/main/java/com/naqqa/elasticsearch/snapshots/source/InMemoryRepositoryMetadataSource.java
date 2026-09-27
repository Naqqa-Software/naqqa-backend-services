package com.naqqa.elasticsearch.snapshots.source;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class InMemoryRepositoryMetadataSource implements RepositoryMetadataSource {

    private final Set<String> indices = new LinkedHashSet<>();
    private final Map<String, Map<String, Object>> settings = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> mappings = new LinkedHashMap<>();
    private final Map<String, Object> legacyTemplates = new LinkedHashMap<>();
    private final Map<String, Object> indexTemplates = new LinkedHashMap<>();
    private final Map<String, Object> componentTemplates = new LinkedHashMap<>();
    private final Map<String, Object> dataStreams = new LinkedHashMap<>();

    public InMemoryRepositoryMetadataSource addIndex(String name, Map<String, Object> settingsMap, Map<String, Object> mappingsMap) {
        indices.add(name);
        settings.put(name, settingsMap);
        mappings.put(name, mappingsMap);
        return this;
    }

    public InMemoryRepositoryMetadataSource addLegacyTemplate(String name, Map<String, Object> body) {
        legacyTemplates.put(name, body);
        return this;
    }

    public InMemoryRepositoryMetadataSource addIndexTemplate(String name, Map<String, Object> body) {
        indexTemplates.put(name, body);
        return this;
    }

    public InMemoryRepositoryMetadataSource addComponentTemplate(String name, Map<String, Object> body) {
        componentTemplates.put(name, body);
        return this;
    }

    public InMemoryRepositoryMetadataSource addDataStream(String name, Map<String, Object> body) {
        dataStreams.put(name, body);
        return this;
    }

    @Override
    public Set<String> listIndices() {
        return Set.copyOf(indices);
    }

    @Override
    public Map<String, Object> indexSettings(String index) {
        return settings.getOrDefault(index, Map.of());
    }

    @Override
    public Map<String, Object> indexMappings(String index) {
        return mappings.getOrDefault(index, Map.of());
    }

    @Override
    public Map<String, Object> legacyTemplates() {
        return Map.copyOf(legacyTemplates);
    }

    @Override
    public Map<String, Object> indexTemplates() {
        return Map.copyOf(indexTemplates);
    }

    @Override
    public Map<String, Object> componentTemplates() {
        return Map.copyOf(componentTemplates);
    }

    @Override
    public Map<String, Object> dataStreams() {
        return Map.copyOf(dataStreams);
    }
}
