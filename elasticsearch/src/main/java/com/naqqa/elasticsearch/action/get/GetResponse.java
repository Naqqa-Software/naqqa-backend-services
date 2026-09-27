package com.naqqa.elasticsearch.action.get;

import com.naqqa.elasticsearch.common.json.JsonValue;

import java.util.Map;

public record GetResponse(String index, String id, boolean found, long version, byte[] source, String error) {

    public static GetResponse notFound(String index, String id) {
        return new GetResponse(index, id, false, -1L, null, null);
    }

    public static GetResponse failed(String index, String id, String error) {
        return new GetResponse(index, id, false, -1L, null, error);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> sourceAsMap() {
        if (source == null) {
            return Map.of();
        }
        return (Map<String, Object>) JsonValue.parse(source).toJava();
    }
}
