package com.naqqa.elasticsearch.search.aggs.support;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class ParamsHelper {

    private ParamsHelper() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object o) {
        if (o == null) {
            return Collections.emptyMap();
        }
        if (o instanceof Map) {
            return (Map<String, Object>) o;
        }
        throw new IllegalArgumentException("expected an object, got " + o.getClass());
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object o) {
        if (o == null) {
            return Collections.emptyList();
        }
        if (o instanceof List) {
            return (List<Object>) o;
        }
        throw new IllegalArgumentException("expected an array, got " + o.getClass());
    }

    public static String getString(Map<String, Object> params, String key, String defaultValue) {
        Object v = params.get(key);
        return v == null ? defaultValue : String.valueOf(v);
    }

    public static String requireString(Map<String, Object> params, String key) {
        Object v = params.get(key);
        if (v == null) {
            throw new IllegalArgumentException("missing required parameter [" + key + "]");
        }
        return String.valueOf(v);
    }

    public static int getInt(Map<String, Object> params, String key, int defaultValue) {
        Object v = params.get(key);
        return v == null ? defaultValue : ((Number) v).intValue();
    }

    public static long getLong(Map<String, Object> params, String key, long defaultValue) {
        Object v = params.get(key);
        return v == null ? defaultValue : ((Number) v).longValue();
    }

    public static double getDouble(Map<String, Object> params, String key, double defaultValue) {
        Object v = params.get(key);
        return v == null ? defaultValue : ((Number) v).doubleValue();
    }

    public static boolean getBoolean(Map<String, Object> params, String key, boolean defaultValue) {
        Object v = params.get(key);
        return v == null ? defaultValue : (Boolean) v;
    }

    @SuppressWarnings("unchecked")
    public static <T> T getRaw(Map<String, Object> params, String key) {
        return (T) params.get(key);
    }
}
