package com.naqqa.elasticsearch.snapshots.model;

import java.util.LinkedHashMap;
import java.util.Map;

public record ShardSnapshotStats(int filesCopied, int filesReused, long bytesCopied) {

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("filesCopied", filesCopied);
        map.put("filesReused", filesReused);
        map.put("bytesCopied", bytesCopied);
        return map;
    }

    @SuppressWarnings("unchecked")
    public static ShardSnapshotStats fromMap(Object raw) {
        Map<String, Object> map = (Map<String, Object>) raw;
        return new ShardSnapshotStats(
                ((Number) map.get("filesCopied")).intValue(),
                ((Number) map.get("filesReused")).intValue(),
                ((Number) map.get("bytesCopied")).longValue());
    }
}
