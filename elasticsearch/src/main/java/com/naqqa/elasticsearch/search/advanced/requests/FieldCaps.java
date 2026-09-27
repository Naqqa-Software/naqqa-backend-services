package com.naqqa.elasticsearch.search.advanced.requests;

import com.naqqa.elasticsearch.codec.fieldinfos.DocValuesType;
import com.naqqa.elasticsearch.codec.fieldinfos.FieldInfo;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class FieldCaps {

    private FieldCaps() {
    }

    public record Capability(String type, boolean searchable, boolean aggregatable, Set<String> indices) {
    }

    public static Map<String, Capability> compute(Map<String, List<FieldInfo>> perShardFieldInfos) {
        Map<String, Boolean> searchable = new LinkedHashMap<>();
        Map<String, Boolean> aggregatable = new LinkedHashMap<>();
        Map<String, String> type = new LinkedHashMap<>();
        Map<String, Set<String>> indices = new LinkedHashMap<>();
        for (Map.Entry<String, List<FieldInfo>> shard : perShardFieldInfos.entrySet()) {
            for (FieldInfo fi : shard.getValue()) {
                String name = fi.name();
                searchable.merge(name, fi.indexed(), (a, b) -> a || b);
                boolean agg = fi.docValuesType() != DocValuesType.NONE;
                aggregatable.merge(name, agg, (a, b) -> a || b);
                type.putIfAbsent(name, inferType(fi));
                indices.computeIfAbsent(name, k -> new LinkedHashSet<>()).add(shard.getKey());
            }
        }
        Map<String, Capability> result = new LinkedHashMap<>();
        for (String field : type.keySet()) {
            result.put(field, new Capability(type.get(field), searchable.getOrDefault(field, false),
                aggregatable.getOrDefault(field, false), indices.get(field)));
        }
        return result;
    }

    private static String inferType(FieldInfo fi) {
        if (fi.docValuesType() == DocValuesType.NUMERIC || fi.docValuesType() == DocValuesType.SORTED_NUMERIC) {
            return "numeric";
        }
        if (fi.pointDimensionCount() > 0) {
            return "numeric";
        }
        if (fi.docValuesType() == DocValuesType.SORTED || fi.docValuesType() == DocValuesType.SORTED_SET) {
            return "keyword";
        }
        if (fi.indexed() && fi.hasNorms()) {
            return "text";
        }
        if (fi.docValuesType() == DocValuesType.BINARY) {
            return "binary";
        }
        return "unknown";
    }
}
