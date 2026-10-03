package com.naqqa.analytics.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class EventRegistry {

    public static final String RESOURCE = "naqqa-analytics/events.schema.json";

    private final int version;
    private final Map<String, EventSpec> events;
    private final Map<String, PropSpec> common;
    private final Set<String> entityTypes;
    private final Set<String> sourceBlocks;
    private final Map<String, Long> limits;

    private EventRegistry(int version, Map<String, EventSpec> events, Map<String, PropSpec> common, Set<String> entityTypes,
                          Set<String> sourceBlocks, Map<String, Long> limits) {
        this.version = version;
        this.events = Collections.unmodifiableMap(events);
        this.common = Collections.unmodifiableMap(common);
        this.entityTypes = Collections.unmodifiableSet(entityTypes);
        this.sourceBlocks = Collections.unmodifiableSet(sourceBlocks);
        this.limits = Collections.unmodifiableMap(limits);
    }

    public static EventRegistry classpath() {
        return classpath(EventRegistry.class.getClassLoader());
    }

    public static EventRegistry classpath(ClassLoader loader) {
        try (InputStream in = loader.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + RESOURCE);
            }
            return load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static EventRegistry load(InputStream in) throws IOException {
        JsonNode root = new ObjectMapper().readTree(in);
        Set<String> entityTypes = strings(root.path("entityTypes"));
        Set<String> sourceBlocks = strings(root.path("sourceBlocks"));
        Map<String, List<String>> refs = Map.of("entityTypes", new ArrayList<>(entityTypes), "sourceBlocks", new ArrayList<>(sourceBlocks));
        Map<String, PropSpec> common = props(root.path("common"), refs);
        Map<String, EventSpec> events = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = root.path("events").fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode n = e.getValue();
            events.put(e.getKey(), new EventSpec(e.getKey(), n.path("group").asText(null), n.path("server").asBoolean(false),
                    Collections.unmodifiableMap(props(n.path("props"), refs))));
        }
        Map<String, Long> limits = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> li = root.path("limits").fields();
        while (li.hasNext()) {
            Map.Entry<String, JsonNode> e = li.next();
            limits.put(e.getKey(), e.getValue().asLong());
        }
        return new EventRegistry(root.path("version").asInt(1), events, common, entityTypes, sourceBlocks, limits);
    }

    private static Set<String> strings(JsonNode node) {
        Set<String> out = new LinkedHashSet<>();
        if (node != null && node.isArray()) {
            node.forEach(v -> out.add(v.asText()));
        }
        return out;
    }

    private static Map<String, PropSpec> props(JsonNode node, Map<String, List<String>> refs) {
        Map<String, PropSpec> out = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return out;
        }
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode p = e.getValue();
            boolean numeric = isNumeric(p);
            List<String> values = new ArrayList<>(strings(p.path("values")));
            if (p.hasNonNull("ref")) {
                values.addAll(refs.getOrDefault(p.get("ref").asText(), List.of()));
            }
            out.put(e.getKey(), new PropSpec(p.path("type").asText("string"),
                    p.hasNonNull("max") && !numeric ? Integer.valueOf(p.get("max").asInt()) : null,
                    p.hasNonNull("min") ? Double.valueOf(p.get("min").asDouble()) : null,
                    p.hasNonNull("max") && numeric ? Double.valueOf(p.get("max").asDouble()) : null,
                    values, p.path("required").asBoolean(false)));
        }
        return out;
    }

    private static boolean isNumeric(JsonNode p) {
        String t = p.path("type").asText("");
        return "int".equals(t) || "number".equals(t);
    }

    public int version() {
        return version;
    }

    public EventSpec event(String name) {
        return name == null ? null : events.get(name);
    }

    public Map<String, EventSpec> events() {
        return events;
    }

    public Map<String, PropSpec> common() {
        return common;
    }

    public Set<String> entityTypes() {
        return entityTypes;
    }

    public Set<String> sourceBlocks() {
        return sourceBlocks;
    }

    public long limit(String key, long fallback) {
        return limits.getOrDefault(key, fallback);
    }
}
