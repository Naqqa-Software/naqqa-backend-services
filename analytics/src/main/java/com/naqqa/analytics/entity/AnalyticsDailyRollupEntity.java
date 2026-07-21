package com.naqqa.analytics.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A durable per-day summary for one (property, entityType, entityId) — written by the rollup
 * scheduler so long-term trends survive after raw events are pruned by retention. Distinct
 * visitor/session counts are per-day (they cannot be summed exactly across days; the query layer
 * computes accurate range totals from raw events while they exist).
 */
@Document("analytics_daily")
@Getter
@Setter
public class AnalyticsDailyRollupEntity {

    /** Composite id: property|entityType|entityId|day. */
    @Id
    private String id;

    @Indexed
    private String property;
    private String entityType;
    private String entityId;
    /** "yyyy-MM-dd" (UTC). */
    @Indexed
    private String day;

    private long views;
    private long visitors;
    private long sessions;
    private long engagedViews;
    private long totalDurationMs;
}
