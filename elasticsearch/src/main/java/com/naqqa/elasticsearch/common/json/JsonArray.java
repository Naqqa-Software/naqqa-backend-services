package com.naqqa.elasticsearch.common.json;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class JsonArray implements JsonValue, Iterable<JsonValue> {

    private final ArrayList<JsonValue> items = new ArrayList<>();

    public JsonArray() {
    }

    public JsonArray add(JsonValue value) {
        items.add(value == null ? JsonValue.NULL : value);
        return this;
    }

    public JsonArray add(String value) {
        return add(JsonValue.of(value));
    }

    public JsonArray add(Number value) {
        return add(JsonValue.of(value));
    }

    public JsonArray add(boolean value) {
        return add(JsonValue.of(value));
    }

    public JsonArray addRaw(Object value) {
        return add(JsonValue.wrap(value));
    }

    public JsonValue get(int index) {
        return items.get(index);
    }

    public int size() {
        return items.size();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    @Override
    public Iterator<JsonValue> iterator() {
        return List.copyOf(items).iterator();
    }

    @Override
    public Object toJava() {
        List<Object> list = new ArrayList<>(items.size());
        for (JsonValue v : items) {
            list.add(v.toJava());
        }
        return list;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof JsonArray other)) {
            return false;
        }
        return items.equals(other.items);
    }

    @Override
    public int hashCode() {
        return items.hashCode();
    }

    @Override
    public String toString() {
        return JsonWriter.toJson(this, false);
    }
}
