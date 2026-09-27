package com.naqqa.elasticsearch.search.advanced.profile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ProfileResult {

    private final String type;
    private final String description;
    private final long timeInNanos;
    private final Map<String, Long> breakdown;
    private final List<ProfileResult> children;

    public ProfileResult(String type, String description, long timeInNanos, Map<String, Long> breakdown, List<ProfileResult> children) {
        this.type = type;
        this.description = description;
        this.timeInNanos = timeInNanos;
        this.breakdown = breakdown;
        this.children = children;
    }

    public String type() {
        return type;
    }

    public String description() {
        return description;
    }

    public long timeInNanos() {
        return timeInNanos;
    }

    public Map<String, Long> breakdown() {
        return breakdown;
    }

    public List<ProfileResult> children() {
        return children;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("description", description);
        m.put("time_in_nanos", timeInNanos);
        m.put("breakdown", breakdown);
        if (!children.isEmpty()) {
            List<Map<String, Object>> childMaps = new java.util.ArrayList<>();
            for (ProfileResult child : children) {
                childMaps.add(child.toMap());
            }
            m.put("children", childMaps);
        }
        return m;
    }
}
