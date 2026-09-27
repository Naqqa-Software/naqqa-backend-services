package com.naqqa.elasticsearch.common.json;

import com.naqqa.elasticsearch.common.xcontent.XContentGenerator;

import java.util.List;
import java.util.Map;

public interface JsonValue {

    JsonValue NULL = new JsonScalar(null);
    JsonValue TRUE = new JsonScalar(Boolean.TRUE);
    JsonValue FALSE = new JsonScalar(Boolean.FALSE);

    Object toJava();

    default boolean isNull() {
        return this == NULL || (this instanceof JsonScalar s && s.value() == null);
    }

    default boolean isObject() {
        return this instanceof JsonObject;
    }

    default boolean isArray() {
        return this instanceof JsonArray;
    }

    default boolean isString() {
        return this instanceof JsonScalar s && s.value() instanceof String;
    }

    default boolean isNumber() {
        return this instanceof JsonScalar s && s.value() instanceof Number;
    }

    default boolean isBoolean() {
        return this instanceof JsonScalar s && s.value() instanceof Boolean;
    }

    default JsonObject asObject() {
        if (this instanceof JsonObject o) {
            return o;
        }
        throw new IllegalStateException("Not an object: " + this);
    }

    default JsonArray asArray() {
        if (this instanceof JsonArray a) {
            return a;
        }
        throw new IllegalStateException("Not an array: " + this);
    }

    default String asString() {
        Object v = toJava();
        return v == null ? null : String.valueOf(v);
    }

    default int asInt() {
        return ((Number) toJava()).intValue();
    }

    default long asLong() {
        return ((Number) toJava()).longValue();
    }

    default double asDouble() {
        return ((Number) toJava()).doubleValue();
    }

    default boolean asBoolean() {
        return (Boolean) toJava();
    }

    default void write(XContentGenerator generator) {
        generator.writeValue(toJava());
    }

    static JsonValue of(String value) {
        return value == null ? NULL : new JsonScalar(value);
    }

    static JsonValue of(boolean value) {
        return value ? TRUE : FALSE;
    }

    static JsonValue of(Number value) {
        return value == null ? NULL : new JsonScalar(value);
    }

    @SuppressWarnings("unchecked")
    static JsonValue wrap(Object value) {
        if (value == null) {
            return NULL;
        }
        if (value instanceof JsonValue v) {
            return v;
        }
        if (value instanceof Map<?, ?> m) {
            JsonObject obj = new JsonObject();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                obj.put(String.valueOf(e.getKey()), wrap(e.getValue()));
            }
            return obj;
        }
        if (value instanceof List<?> l) {
            JsonArray arr = new JsonArray();
            for (Object o : l) {
                arr.add(wrap(o));
            }
            return arr;
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return new JsonScalar(value);
        }
        return new JsonScalar(String.valueOf(value));
    }

    static JsonValue parse(String content) {
        try (JsonParser parser = new JsonParser(content)) {
            parser.nextToken();
            return wrap(parser.readValue());
        }
    }

    static JsonValue parse(byte[] content) {
        try (JsonParser parser = new JsonParser(content)) {
            parser.nextToken();
            return wrap(parser.readValue());
        }
    }
}
