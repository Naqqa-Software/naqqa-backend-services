package com.naqqa.elasticsearch.cluster.state;

import com.naqqa.elasticsearch.cluster.io.StreamUtils;
import com.naqqa.elasticsearch.cluster.io.Writeable;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Settings implements Writeable {

    public static final Settings EMPTY = new Settings(Map.of());

    private final Map<String, String> values;

    private Settings(Map<String, String> values) {
        this.values = Collections.unmodifiableMap(values);
    }

    public String get(String key) {
        return values.get(key);
    }

    public String get(String key, String defaultValue) {
        return values.getOrDefault(key, defaultValue);
    }

    public int getAsInt(String key, int defaultValue) {
        String v = values.get(key);
        return v == null ? defaultValue : Integer.parseInt(v.trim());
    }

    public long getAsLong(String key, long defaultValue) {
        String v = values.get(key);
        return v == null ? defaultValue : Long.parseLong(v.trim());
    }

    public boolean getAsBoolean(String key, boolean defaultValue) {
        String v = values.get(key);
        return v == null ? defaultValue : Boolean.parseBoolean(v.trim());
    }

    public double getAsDouble(String key, double defaultValue) {
        String v = values.get(key);
        return v == null ? defaultValue : Double.parseDouble(v.trim());
    }

    public Map<String, String> getAsMap() {
        return values;
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public Map<String, String> getByPrefix(String prefix) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                result.put(entry.getKey().substring(prefix.length()), entry.getValue());
            }
        }
        return result;
    }

    public Settings merge(Settings other) {
        Map<String, String> merged = new LinkedHashMap<>(values);
        merged.putAll(other.values);
        return new Settings(merged);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public void writeTo(DataOutput out) throws IOException {
        StreamUtils.writeStringMap(out, values);
    }

    public static Settings readFrom(DataInput in) throws IOException {
        return new Settings(StreamUtils.readStringMap(in));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Settings other && values.equals(other.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return values.toString();
    }

    public static final class Builder {
        private final Map<String, String> values = new LinkedHashMap<>();

        public Builder put(String key, String value) {
            values.put(key, value);
            return this;
        }

        public Builder put(String key, int value) {
            return put(key, Integer.toString(value));
        }

        public Builder put(String key, long value) {
            return put(key, Long.toString(value));
        }

        public Builder put(String key, boolean value) {
            return put(key, Boolean.toString(value));
        }

        public Builder putAll(Settings settings) {
            values.putAll(settings.values);
            return this;
        }

        public Builder putAll(Map<String, String> map) {
            values.putAll(map);
            return this;
        }

        public Settings build() {
            return new Settings(new LinkedHashMap<>(values));
        }
    }
}
