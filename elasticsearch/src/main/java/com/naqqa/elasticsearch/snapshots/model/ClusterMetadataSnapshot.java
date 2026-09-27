package com.naqqa.elasticsearch.snapshots.model;

import com.naqqa.elasticsearch.snapshots.json.Json;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public record ClusterMetadataSnapshot(
        Map<String, Map<String, Object>> indexSettings,
        Map<String, Map<String, Object>> indexMappings,
        Map<String, Object> legacyTemplates,
        Map<String, Object> indexTemplates,
        Map<String, Object> componentTemplates,
        Map<String, Object> dataStreams) {

    public byte[] toBytes() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("indexSettings", indexSettings);
        map.put("indexMappings", indexMappings);
        map.put("legacyTemplates", legacyTemplates);
        map.put("indexTemplates", indexTemplates);
        map.put("componentTemplates", componentTemplates);
        map.put("dataStreams", dataStreams);
        return Json.write(map).getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    public static ClusterMetadataSnapshot fromBytes(byte[] bytes) {
        Map<String, Object> map = Json.parseObject(new String(bytes, StandardCharsets.UTF_8));
        return new ClusterMetadataSnapshot(
                (Map<String, Map<String, Object>>) (Map<String, ?>) map.getOrDefault("indexSettings", Map.of()),
                (Map<String, Map<String, Object>>) (Map<String, ?>) map.getOrDefault("indexMappings", Map.of()),
                (Map<String, Object>) map.getOrDefault("legacyTemplates", Map.of()),
                (Map<String, Object>) map.getOrDefault("indexTemplates", Map.of()),
                (Map<String, Object>) map.getOrDefault("componentTemplates", Map.of()),
                (Map<String, Object>) map.getOrDefault("dataStreams", Map.of()));
    }
}
