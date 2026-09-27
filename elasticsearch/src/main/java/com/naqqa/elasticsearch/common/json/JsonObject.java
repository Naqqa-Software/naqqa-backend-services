package com.naqqa.elasticsearch.common.json;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class JsonObject implements JsonValue, Iterable<Map.Entry<String, JsonValue>> {

    private final LinkedHashMap<String, JsonValue> fields = new LinkedHashMap<>();

    public JsonObject() {
    }

    public JsonObject put(String name, JsonValue value) {
        fields.put(name, value == null ? JsonValue.NULL : value);
        return this;
    }

    public JsonObject put(String name, String value) {
        return put(name, JsonValue.of(value));
    }

    public JsonObject put(String name, Number value) {
        return put(name, JsonValue.of(value));
    }

    public JsonObject put(String name, boolean value) {
        return put(name, JsonValue.of(value));
    }

    public JsonObject putRaw(String name, Object value) {
        return put(name, JsonValue.wrap(value));
    }

    public JsonValue get(String name) {
        return fields.get(name);
    }

    public boolean has(String name) {
        return fields.containsKey(name);
    }

    public JsonValue remove(String name) {
        return fields.remove(name);
    }

    public int size() {
        return fields.size();
    }

    public boolean isEmpty() {
        return fields.isEmpty();
    }

    public Set<String> keySet() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(fields.keySet()));
    }

    public String getString(String name) {
        JsonValue v = fields.get(name);
        return v == null || v.isNull() ? null : v.asString();
    }

    public String getString(String name, String defaultValue) {
        String v = getString(name);
        return v == null ? defaultValue : v;
    }

    public int getInt(String name, int defaultValue) {
        JsonValue v = fields.get(name);
        return v == null || v.isNull() ? defaultValue : v.asInt();
    }

    public long getLong(String name, long defaultValue) {
        JsonValue v = fields.get(name);
        return v == null || v.isNull() ? defaultValue : v.asLong();
    }

    public double getDouble(String name, double defaultValue) {
        JsonValue v = fields.get(name);
        return v == null || v.isNull() ? defaultValue : v.asDouble();
    }

    public boolean getBoolean(String name, boolean defaultValue) {
        JsonValue v = fields.get(name);
        return v == null || v.isNull() ? defaultValue : v.asBoolean();
    }

    public JsonObject getObject(String name) {
        JsonValue v = fields.get(name);
        return v == null || v.isNull() ? null : v.asObject();
    }

    public JsonArray getArray(String name) {
        JsonValue v = fields.get(name);
        return v == null || v.isNull() ? null : v.asArray();
    }

    @Override
    public Iterator<Map.Entry<String, JsonValue>> iterator() {
        return Collections.unmodifiableMap(fields).entrySet().iterator();
    }

    @Override
    public Object toJava() {
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<String, JsonValue> e : fields.entrySet()) {
            map.put(e.getKey(), e.getValue().toJava());
        }
        return map;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof JsonObject other)) {
            return false;
        }
        return fields.equals(other.fields);
    }

    @Override
    public int hashCode() {
        return fields.hashCode();
    }

    @Override
    public String toString() {
        return JsonWriter.toJson(this, false);
    }
}
