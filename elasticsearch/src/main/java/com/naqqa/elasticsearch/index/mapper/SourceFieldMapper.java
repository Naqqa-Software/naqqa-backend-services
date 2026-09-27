package com.naqqa.elasticsearch.index.mapper;

import com.naqqa.elasticsearch.common.json.JsonObject;
import com.naqqa.elasticsearch.common.json.JsonValue;
import com.naqqa.elasticsearch.common.json.JsonWriter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class SourceFieldMapper extends MetadataFieldMapper {

    public static final String NAME = "_source";

    private final boolean enabled;
    private final List<String> includes;
    private final List<String> excludes;

    public SourceFieldMapper(boolean enabled, List<String> includes, List<String> excludes) {
        super(NAME);
        this.enabled = enabled;
        this.includes = includes;
        this.excludes = excludes;
    }

    @Override
    public String typeName() {
        return "_source";
    }

    public boolean enabled() {
        return enabled;
    }

    public JsonObject filter(JsonObject source) {
        if (!enabled) {
            return null;
        }
        if (includes.isEmpty() && excludes.isEmpty()) {
            return source;
        }
        List<Map.Entry<String, JsonValue>> leaves = new ArrayList<>();
        flatten("", source, leaves);
        List<Map.Entry<String, JsonValue>> kept = new ArrayList<>();
        for (Map.Entry<String, JsonValue> e : leaves) {
            boolean included = includes.isEmpty() || matchesAny(includes, e.getKey());
            boolean excluded = matchesAny(excludes, e.getKey());
            if (included && !excluded) {
                kept.add(e);
            }
        }
        return rebuild(kept);
    }

    public void createField(ParseContext context, JsonObject filtered) {
        if (!enabled) {
            return;
        }
        byte[] bytes = JsonWriter.toJson(filtered, false).getBytes(StandardCharsets.UTF_8);
        context.addIndexableField(IndexableField.stored(NAME, bytes));
    }

    private static void flatten(String path, JsonObject obj, List<Map.Entry<String, JsonValue>> out) {
        for (Map.Entry<String, JsonValue> e : obj) {
            String p = path.isEmpty() ? e.getKey() : path + "." + e.getKey();
            JsonValue v = e.getValue();
            if (v.isObject()) {
                flatten(p, v.asObject(), out);
            } else {
                out.add(Map.entry(p, v));
            }
        }
    }

    private static JsonObject rebuild(List<Map.Entry<String, JsonValue>> kept) {
        JsonObject root = new JsonObject();
        for (Map.Entry<String, JsonValue> e : kept) {
            String[] parts = e.getKey().split("\\.");
            JsonObject cur = root;
            for (int i = 0; i < parts.length - 1; i++) {
                JsonValue existing = cur.get(parts[i]);
                JsonObject next;
                if (existing instanceof JsonObject jo) {
                    next = jo;
                } else {
                    next = new JsonObject();
                    cur.put(parts[i], next);
                }
                cur = next;
            }
            cur.put(parts[parts.length - 1], e.getValue());
        }
        return root;
    }

    private static boolean matchesAny(List<String> patterns, String path) {
        for (String p : patterns) {
            if (matches(p, path)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(String pattern, String path) {
        if (pattern.equals(path) || path.startsWith(pattern + ".")) {
            return true;
        }
        if (pattern.indexOf('*') < 0 && pattern.indexOf('?') < 0) {
            return false;
        }
        return path.matches(globToRegex(pattern));
    }

    private static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                case '.' -> sb.append("\\.");
                default -> {
                    if ("\\^$|()[]{}+".indexOf(c) >= 0) {
                        sb.append('\\');
                    }
                    sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
