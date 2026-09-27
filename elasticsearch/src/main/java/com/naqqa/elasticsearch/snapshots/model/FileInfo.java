package com.naqqa.elasticsearch.snapshots.model;

import java.util.LinkedHashMap;
import java.util.Map;

public record FileInfo(String originalName, String blobName, long length, String checksum) {

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", originalName);
        map.put("blob", blobName);
        map.put("length", length);
        map.put("checksum", checksum);
        return map;
    }

    @SuppressWarnings("unchecked")
    public static FileInfo fromMap(Object raw) {
        Map<String, Object> map = (Map<String, Object>) raw;
        return new FileInfo(
                (String) map.get("name"),
                (String) map.get("blob"),
                ((Number) map.get("length")).longValue(),
                (String) map.get("checksum"));
    }
}
