package com.naqqa.elasticsearch.node.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class SettingsMaps {

    private SettingsMaps() {
    }

    public static Map<String, String> flatten(Map<String, ?> nested) {
        Map<String, String> out = new LinkedHashMap<>();
        if (nested != null) {
            flattenInto("", nested, out);
        }
        return out;
    }

    private static void flattenInto(String prefix, Object value, Map<String, String> out) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = prefix.isEmpty() ? String.valueOf(e.getKey()) : prefix + "." + e.getKey();
                flattenInto(key, e.getValue(), out);
            }
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                flattenInto(prefix + "." + i, list.get(i), out);
            }
        } else if (value != null) {
            out.put(prefix, String.valueOf(value));
        }
    }

    public static Map<String, String> flattenIndexSettings(Map<String, ?> nested) {
        Map<String, String> flat = flatten(nested);
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : flat.entrySet()) {
            String key = e.getKey().startsWith("index.") ? e.getKey() : "index." + e.getKey();
            out.put(key, e.getValue());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> unflatten(Map<String, String> flat) {
        Map<String, Object> root = new TreeMap<>();
        for (Map.Entry<String, String> e : flat.entrySet()) {
            String[] parts = e.getKey().split("\\.");
            Map<String, Object> current = root;
            for (int i = 0; i < parts.length - 1; i++) {
                Object next = current.get(parts[i]);
                if (!(next instanceof Map)) {
                    next = new TreeMap<String, Object>();
                    current.put(parts[i], next);
                }
                current = (Map<String, Object>) next;
            }
            current.put(parts[parts.length - 1], e.getValue());
        }
        return (Map<String, Object>) listify(root);
    }

    private static Object listify(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return value;
        }
        boolean allIndexes = !map.isEmpty();
        for (Object key : map.keySet()) {
            if (!String.valueOf(key).matches("\\d+")) {
                allIndexes = false;
                break;
            }
        }
        if (allIndexes) {
            TreeMap<Integer, Object> ordered = new TreeMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                ordered.put(Integer.parseInt(String.valueOf(e.getKey())), listify(e.getValue()));
            }
            if (ordered.firstKey() == 0 && ordered.lastKey() == ordered.size() - 1) {
                return new ArrayList<>(ordered.values());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            out.put(String.valueOf(e.getKey()), listify(e.getValue()));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        return null;
    }

    public static List<String> asStringList(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object o : list) {
                out.add(String.valueOf(o));
            }
        } else if (value != null) {
            for (String s : String.valueOf(value).split(",")) {
                if (!s.isBlank()) {
                    out.add(s.trim());
                }
            }
        }
        return out;
    }
}
