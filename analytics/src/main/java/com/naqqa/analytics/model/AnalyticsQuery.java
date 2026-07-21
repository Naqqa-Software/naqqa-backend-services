package com.naqqa.analytics.model;

import java.time.Instant;

/**
 * Filter for the query API: a property, an optional entity type/id (drill-down), a time range and a
 * timeseries granularity. Nulls mean "all".
 */
public record AnalyticsQuery(
        String property,
        String entityType,
        String entityId,
        String path,          // drill into a single URL path (any sitemap page)
        Instant from,
        Instant to,
        Granularity granularity
) {
    public enum Granularity { DAY, WEEK, MONTH, HOUR }
}
