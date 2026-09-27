package com.naqqa.elasticsearch.http;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class HttpHeaders {

    private final Map<String, List<String>> byLowerName = new LinkedHashMap<>();
    private final Map<String, String> originalName = new LinkedHashMap<>();

    public void add(String name, String value) {
        String lower = name.toLowerCase(Locale.ROOT);
        originalName.putIfAbsent(lower, name);
        byLowerName.computeIfAbsent(lower, k -> new ArrayList<>()).add(value);
    }

    public void set(String name, String value) {
        String lower = name.toLowerCase(Locale.ROOT);
        originalName.put(lower, name);
        List<String> values = new ArrayList<>();
        values.add(value);
        byLowerName.put(lower, values);
    }

    public String getFirst(String name) {
        List<String> values = byLowerName.get(name.toLowerCase(Locale.ROOT));
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }

    public List<String> get(String name) {
        List<String> values = byLowerName.get(name.toLowerCase(Locale.ROOT));
        return values == null ? Collections.emptyList() : Collections.unmodifiableList(values);
    }

    public boolean contains(String name) {
        return byLowerName.containsKey(name.toLowerCase(Locale.ROOT));
    }

    public Map<String, List<String>> asMap() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : byLowerName.entrySet()) {
            result.put(originalName.getOrDefault(entry.getKey(), entry.getKey()), List.copyOf(entry.getValue()));
        }
        return result;
    }

    public java.util.Set<String> names() {
        java.util.Set<String> result = new java.util.LinkedHashSet<>();
        for (String lower : byLowerName.keySet()) {
            result.add(originalName.getOrDefault(lower, lower));
        }
        return result;
    }
}
