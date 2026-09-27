package com.naqqa.elasticsearch.snapshots.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SnapshotInfo(
        String id,
        String name,
        String repository,
        List<String> indices,
        SnapshotState state,
        long startTimeMillis,
        Long endTimeMillis,
        String failureReason,
        String policyId,
        Map<String, String> shardManifestBlobs,
        Map<String, ShardSnapshotStats> shardStats,
        String metadataBlob) {

    public Instant startTime() {
        return Instant.ofEpochMilli(startTimeMillis);
    }

    public Instant endTime() {
        return endTimeMillis == null ? null : Instant.ofEpochMilli(endTimeMillis);
    }

    public SnapshotInfo withState(SnapshotState newState, Long newEndTimeMillis, String newFailureReason) {
        return new SnapshotInfo(id, name, repository, indices, newState, startTimeMillis, newEndTimeMillis,
                newFailureReason, policyId, shardManifestBlobs, shardStats, metadataBlob);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("name", name);
        map.put("repository", repository);
        map.put("indices", new ArrayList<>(indices));
        map.put("state", state.name());
        map.put("startTime", startTimeMillis);
        if (endTimeMillis != null) {
            map.put("endTime", endTimeMillis);
        }
        if (failureReason != null) {
            map.put("failureReason", failureReason);
        }
        if (policyId != null) {
            map.put("policyId", policyId);
        }
        map.put("shardManifestBlobs", new LinkedHashMap<>(shardManifestBlobs));
        Map<String, Object> statsMap = new LinkedHashMap<>();
        for (Map.Entry<String, ShardSnapshotStats> e : shardStats.entrySet()) {
            statsMap.put(e.getKey(), e.getValue().toMap());
        }
        map.put("shardStats", statsMap);
        if (metadataBlob != null) {
            map.put("metadataBlob", metadataBlob);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    public static SnapshotInfo fromMap(Object raw) {
        Map<String, Object> map = (Map<String, Object>) raw;
        List<Object> rawIndices = (List<Object>) map.get("indices");
        List<String> indices = new ArrayList<>();
        for (Object o : rawIndices) {
            indices.add((String) o);
        }
        Map<String, Object> rawManifests = (Map<String, Object>) map.getOrDefault("shardManifestBlobs", Map.of());
        Map<String, String> shardManifestBlobs = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : rawManifests.entrySet()) {
            shardManifestBlobs.put(e.getKey(), (String) e.getValue());
        }
        Map<String, Object> rawStats = (Map<String, Object>) map.getOrDefault("shardStats", Map.of());
        Map<String, ShardSnapshotStats> shardStats = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : rawStats.entrySet()) {
            shardStats.put(e.getKey(), ShardSnapshotStats.fromMap(e.getValue()));
        }
        Number endTime = (Number) map.get("endTime");
        return new SnapshotInfo(
                (String) map.get("id"),
                (String) map.get("name"),
                (String) map.get("repository"),
                indices,
                SnapshotState.valueOf((String) map.get("state")),
                ((Number) map.get("startTime")).longValue(),
                endTime == null ? null : endTime.longValue(),
                (String) map.get("failureReason"),
                (String) map.get("policyId"),
                shardManifestBlobs,
                shardStats,
                (String) map.get("metadataBlob"));
    }
}
