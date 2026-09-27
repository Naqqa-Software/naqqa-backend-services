package com.naqqa.elasticsearch.monitor.health;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record HealthIndicatorResult(String name, HealthStatus status, String symptom, List<String> diagnosis) {

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", status.name());
        map.put("symptom", symptom);
        map.put("diagnosis", diagnosis);
        return map;
    }
}
