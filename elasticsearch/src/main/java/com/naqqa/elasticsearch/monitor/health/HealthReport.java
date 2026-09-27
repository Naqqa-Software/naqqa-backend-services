package com.naqqa.elasticsearch.monitor.health;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HealthReport {

    private final List<HealthIndicatorSource> indicatorSources;

    public HealthReport(List<HealthIndicatorSource> indicatorSources) {
        this.indicatorSources = indicatorSources;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        Map<String, Object> indicators = new LinkedHashMap<>();
        HealthStatus overall = HealthStatus.GREEN;
        for (HealthIndicatorSource source : indicatorSources) {
            HealthIndicatorResult result = source.calculate();
            indicators.put(result.name(), result.toMap());
            overall = HealthStatus.worstOf(overall, result.status());
        }
        map.put("status", overall.name());
        map.put("indicators", indicators);
        return map;
    }

    public HealthStatus overallStatus() {
        HealthStatus overall = HealthStatus.GREEN;
        for (HealthIndicatorSource source : indicatorSources) {
            overall = HealthStatus.worstOf(overall, source.calculate().status());
        }
        return overall;
    }
}
