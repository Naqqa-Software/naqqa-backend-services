package com.naqqa.elasticsearch.indices.template;

import java.util.LinkedHashMap;
import java.util.Map;

public final class MappingMerger {

    private MappingMerger() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> overlay) {
        Map<String, Object> result = new LinkedHashMap<>(base == null ? Map.of() : base);
        if (overlay == null) {
            return result;
        }
        for (Map.Entry<String, Object> entry : overlay.entrySet()) {
            Object existing = result.get(entry.getKey());
            Object incoming = entry.getValue();
            if (existing instanceof Map && incoming instanceof Map) {
                result.put(entry.getKey(), merge((Map<String, Object>) existing, (Map<String, Object>) incoming));
            } else {
                result.put(entry.getKey(), incoming);
            }
        }
        return result;
    }
}
