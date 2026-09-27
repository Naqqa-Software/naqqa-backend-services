package com.naqqa.elasticsearch.action.write;

import com.naqqa.elasticsearch.common.bytes.BytesReference;
import com.naqqa.elasticsearch.common.json.JsonValue;
import com.naqqa.elasticsearch.common.regex.Regex;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SourceUtils {

    private SourceUtils() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> decode(BytesReference source) {
        if (source == null) {
            return null;
        }
        return (Map<String, Object>) JsonValue.parse(source.toBytesArray()).toJava();
    }

    public static Map<String, Object> filterSource(Map<String, Object> source, List<String> includes, List<String> excludes) {
        if (source == null) {
            return null;
        }
        if ((includes == null || includes.isEmpty()) && (excludes == null || excludes.isEmpty())) {
            return new LinkedHashMap<>(source);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            boolean included = includes == null || includes.isEmpty() || matchesAny(includes, key);
            boolean excluded = excludes != null && matchesAny(excludes, key);
            if (included && !excluded) {
                result.put(key, entry.getValue());
            }
        }
        return result;
    }

    private static boolean matchesAny(List<String> patterns, String field) {
        for (String pattern : patterns) {
            if (Regex.isSimpleMatchPattern(pattern) ? Regex.simpleMatch(pattern, field) : pattern.equals(field)) {
                return true;
            }
        }
        return false;
    }
}
