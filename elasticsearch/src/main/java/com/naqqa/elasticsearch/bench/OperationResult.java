package com.naqqa.elasticsearch.bench;

import java.util.LinkedHashMap;
import java.util.Map;

public record OperationResult(String name, long iterations, double opsPerSec, double p50Ms, double p90Ms, double p99Ms,
                               double maxMs, long errorCount, long hitCount, String note) {

    public static OperationResult unsupported(String name, String note) {
        return new OperationResult(name, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, -1, "unsupported: " + note);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("iterations", iterations);
        m.put("ops_per_sec", opsPerSec);
        m.put("p50_ms", p50Ms);
        m.put("p90_ms", p90Ms);
        m.put("p99_ms", p99Ms);
        m.put("max_ms", maxMs);
        m.put("error_count", errorCount);
        m.put("hit_count", hitCount);
        m.put("note", note);
        return m;
    }
}
