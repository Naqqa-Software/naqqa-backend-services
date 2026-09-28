package com.naqqa.elasticsearch.cluster.state;

import com.naqqa.elasticsearch.cluster.io.StreamUtils;
import com.naqqa.elasticsearch.common.json.JsonValue;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A generic {@link Custom} cluster-state entry that stores a named collection of JSON-like
 * documents (id -&gt; body map), used for every node-local admin store that needs to become
 * cluster-wide and persisted: ingest pipelines, stored scripts, ILM policies, snapshot
 * repositories, SLM policies and the native security realm's users/roles/role-mappings/API keys.
 */
public final class MapCustom implements Custom {

    private final String name;
    private final Map<String, String> entries;

    public MapCustom(String name, Map<String, String> entries) {
        this.name = name;
        this.entries = Map.copyOf(entries);
    }

    public static MapCustom empty(String name) {
        return new MapCustom(name, Map.of());
    }

    @Override
    public String getWriteableName() {
        return name;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public Set<String> ids() {
        return entries.keySet();
    }

    public boolean contains(String id) {
        return entries.containsKey(id);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> get(String id) {
        String json = entries.get(id);
        if (json == null) {
            return null;
        }
        return (Map<String, Object>) JsonValue.parse(json).toJava();
    }

    public Map<String, Map<String, Object>> asMap() {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (String id : entries.keySet()) {
            out.put(id, get(id));
        }
        return out;
    }

    public MapCustom with(String id, Map<String, Object> value) {
        Map<String, String> copy = new LinkedHashMap<>(entries);
        copy.put(id, JsonValue.wrap(value).toString());
        return new MapCustom(name, copy);
    }

    public MapCustom without(String id) {
        if (!entries.containsKey(id)) {
            return this;
        }
        Map<String, String> copy = new LinkedHashMap<>(entries);
        copy.remove(id);
        return new MapCustom(name, copy);
    }

    @Override
    public void writeTo(DataOutput out) throws IOException {
        StreamUtils.writeVInt(out, entries.size());
        for (Map.Entry<String, String> e : entries.entrySet()) {
            StreamUtils.writeString(out, e.getKey());
            StreamUtils.writeString(out, e.getValue());
        }
    }

    public static MapCustom readFrom(String name, DataInput in) throws IOException {
        int count = StreamUtils.readVInt(in);
        Map<String, String> entries = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            String key = StreamUtils.readString(in);
            String value = StreamUtils.readString(in);
            entries.put(key, value);
        }
        return new MapCustom(name, entries);
    }
}
