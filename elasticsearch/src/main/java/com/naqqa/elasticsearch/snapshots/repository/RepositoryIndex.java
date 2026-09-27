package com.naqqa.elasticsearch.snapshots.repository;

import com.naqqa.elasticsearch.snapshots.json.Json;
import com.naqqa.elasticsearch.snapshots.model.SnapshotInfo;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record RepositoryIndex(long generation, List<SnapshotInfo> snapshots) {

    public byte[] toBytes() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("generation", generation);
        List<Object> list = new ArrayList<>();
        for (SnapshotInfo info : snapshots) {
            list.add(info.toMap());
        }
        map.put("snapshots", list);
        return Json.write(map).getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    public static RepositoryIndex fromBytes(byte[] bytes) {
        Map<String, Object> map = Json.parseObject(new String(bytes, StandardCharsets.UTF_8));
        long generation = ((Number) map.get("generation")).longValue();
        List<Object> rawSnapshots = (List<Object>) map.getOrDefault("snapshots", List.of());
        List<SnapshotInfo> snapshots = new ArrayList<>();
        for (Object raw : rawSnapshots) {
            snapshots.add(SnapshotInfo.fromMap(raw));
        }
        return new RepositoryIndex(generation, snapshots);
    }

    public static RepositoryIndex empty() {
        return new RepositoryIndex(-1, List.of());
    }
}
