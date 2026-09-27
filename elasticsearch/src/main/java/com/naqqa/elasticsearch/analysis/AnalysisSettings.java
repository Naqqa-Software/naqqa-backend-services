package com.naqqa.elasticsearch.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class AnalysisSettings {

    public static final AnalysisSettings EMPTY = new AnalysisSettings(Collections.emptyMap());

    private final Map<String, Object> values;

    private AnalysisSettings(Map<String, Object> values) {
        this.values = values;
    }

    public static AnalysisSettings of(Map<String, ?> raw) {
        if (raw == null || raw.isEmpty()) {
            return EMPTY;
        }
        return new AnalysisSettings(normalize(raw));
    }

    public static AnalysisSettings ofPairs(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            m.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return of(m);
    }

    public Map<String, Object> asMap() {
        return Collections.unmodifiableMap(values);
    }

    public Set<String> keys() {
        return values.keySet();
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public boolean has(String key) {
        return get(key) != null;
    }

    public Object get(String key) {
        Object direct = values.get(key);
        if (direct != null) {
            return direct;
        }
        int dot = key.indexOf('.');
        if (dot < 0) {
            return null;
        }
        Object current = values;
        for (String part : key.split("\\.")) {
            if (current instanceof Map<?, ?> m) {
                current = m.get(part);
            } else if (current instanceof List<?> l) {
                try {
                    int idx = Integer.parseInt(part);
                    current = idx >= 0 && idx < l.size() ? l.get(idx) : null;
                } catch (NumberFormatException e) {
                    return null;
                }
            } else {
                return null;
            }
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    public AnalysisSettings getAsSettings(String key) {
        Object v = get(key);
        if (v instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) m;
            return new AnalysisSettings(cast);
        }
        return EMPTY;
    }

    public String getString(String key) {
        return getString(key, null);
    }

    public String getString(String key, String defaultValue) {
        Object v = get(key);
        if (v == null) {
            return defaultValue;
        }
        if (v instanceof List<?> || v instanceof Map<?, ?>) {
            throw new IllegalArgumentException("Failed to get setting [" + key + "] as a single value: setting is not a single value");
        }
        return String.valueOf(v);
    }

    public int getInt(String key, int defaultValue) {
        Object v = get(key);
        if (v == null) {
            return defaultValue;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        String s = String.valueOf(v).trim();
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            try {
                double d = Double.parseDouble(s);
                if (d == Math.rint(d)) {
                    return (int) d;
                }
            } catch (NumberFormatException ignored) {
            }
            throw new IllegalArgumentException("Failed to parse value [" + s + "] for setting [" + key + "]");
        }
    }

    public Integer getInteger(String key) {
        return has(key) ? getInt(key, 0) : null;
    }

    public long getLong(String key, long defaultValue) {
        Object v = get(key);
        if (v == null) {
            return defaultValue;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Failed to parse value [" + v + "] for setting [" + key + "]");
        }
    }

    public double getDouble(String key, double defaultValue) {
        Object v = get(key);
        if (v == null) {
            return defaultValue;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Failed to parse value [" + v + "] for setting [" + key + "]");
        }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        Object v = get(key);
        if (v == null) {
            return defaultValue;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(v);
        if ("true".equals(s)) {
            return true;
        }
        if ("false".equals(s)) {
            return false;
        }
        throw new IllegalArgumentException("Failed to parse value [" + s + "] as only [true] or [false] are allowed.");
    }

    public List<String> getList(String key) {
        return getList(key, false);
    }

    public List<String> getList(String key, boolean splitCommas) {
        Object v = get(key);
        if (v == null) {
            return null;
        }
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            for (Object o : l) {
                if (o != null) {
                    out.add(String.valueOf(o));
                }
            }
        } else if (v instanceof Object[] arr) {
            for (Object o : arr) {
                if (o != null) {
                    out.add(String.valueOf(o));
                }
            }
        } else if (v instanceof Map<?, ?> m) {
            for (Object o : m.values()) {
                out.add(String.valueOf(o));
            }
        } else {
            String s = String.valueOf(v);
            if (splitCommas) {
                for (String part : s.split(",")) {
                    String trimmed = part.trim();
                    if (!trimmed.isEmpty()) {
                        out.add(trimmed);
                    }
                }
            } else {
                out.add(s);
            }
        }
        return out;
    }

    @Override
    public String toString() {
        return values.toString();
    }

    public static Map<String, Object> normalize(Map<String, ?> raw) {
        Map<String, Object> flat = new LinkedHashMap<>();
        flatten("", raw, flat);
        Map<String, Object> root = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : flat.entrySet()) {
            insert(root, e.getKey().split("\\."), 0, e.getValue());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) listify(root);
        return result;
    }

    private static void flatten(String prefix, Object value, Map<String, Object> out) {
        if (value instanceof Map<?, ?> m) {
            if (m.isEmpty() && !prefix.isEmpty()) {
                out.put(prefix, new LinkedHashMap<String, Object>());
                return;
            }
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String k = String.valueOf(e.getKey());
                flatten(prefix.isEmpty() ? k : prefix + "." + k, e.getValue(), out);
            }
        } else if (value instanceof List<?> l) {
            if (l.isEmpty()) {
                out.put(prefix, new ArrayList<>());
                return;
            }
            boolean scalars = true;
            for (Object o : l) {
                if (o instanceof Map<?, ?> || o instanceof List<?>) {
                    scalars = false;
                    break;
                }
            }
            if (scalars) {
                out.put(prefix, new ArrayList<>(l));
            } else {
                for (int i = 0; i < l.size(); i++) {
                    flatten(prefix + "." + i, l.get(i), out);
                }
            }
        } else if (value instanceof Object[] arr) {
            flatten(prefix, java.util.Arrays.asList(arr), out);
        } else {
            out.put(prefix, value);
        }
    }

    @SuppressWarnings("unchecked")
    private static void insert(Map<String, Object> node, String[] parts, int idx, Object value) {
        String part = parts[idx];
        if (idx == parts.length - 1) {
            Object existing = node.get(part);
            if (existing instanceof Map<?, ?> em && value instanceof Map<?, ?> vm && vm.isEmpty()) {
                return;
            }
            node.put(part, value);
            return;
        }
        Object child = node.get(part);
        if (!(child instanceof Map<?, ?>)) {
            Map<String, Object> created = new LinkedHashMap<>();
            if (child instanceof List<?> l) {
                for (int i = 0; i < l.size(); i++) {
                    created.put(String.valueOf(i), l.get(i));
                }
            }
            node.put(part, created);
            child = created;
        }
        insert((Map<String, Object>) child, parts, idx + 1, value);
    }

    private static Object listify(Object node) {
        if (!(node instanceof Map<?, ?> m)) {
            return node;
        }
        Map<String, Object> converted = new LinkedHashMap<>();
        boolean allNumeric = !m.isEmpty();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String k = String.valueOf(e.getKey());
            converted.put(k, listify(e.getValue()));
            if (allNumeric && !isIndex(k)) {
                allNumeric = false;
            }
        }
        if (allNumeric) {
            int n = converted.size();
            Object[] arr = new Object[n];
            for (Map.Entry<String, Object> e : converted.entrySet()) {
                int i = Integer.parseInt(e.getKey());
                if (i >= n) {
                    return converted;
                }
                arr[i] = e.getValue();
            }
            List<Object> list = new ArrayList<>(n);
            Collections.addAll(list, arr);
            return list;
        }
        return converted;
    }

    private static boolean isIndex(String k) {
        if (k.isEmpty() || k.length() > 9) {
            return false;
        }
        for (int i = 0; i < k.length(); i++) {
            char c = k.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
