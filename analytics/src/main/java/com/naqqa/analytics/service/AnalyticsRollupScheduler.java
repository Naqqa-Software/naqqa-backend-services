package com.naqqa.analytics.service;

import com.naqqa.analytics.config.AnalyticsProperties;
import com.naqqa.analytics.entity.AnalyticsDailyRollupEntity;
import com.naqqa.analytics.repository.AnalyticsDailyRollupRepository;
import com.naqqa.analytics.repository.AnalyticsEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.springframework.data.mongodb.core.aggregation.Aggregation.*;

/**
 * Nightly maintenance: writes a durable per-day rollup (so long-term trends survive raw pruning) and
 * deletes raw events older than the retention window. Needs the host's {@code @EnableScheduling}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AnalyticsRollupScheduler {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private static final String COLL = "analytics_events";

    private final AnalyticsProperties props;
    private final MongoTemplate mongo;
    private final AnalyticsDailyRollupRepository rollups;
    private final AnalyticsEventRepository events;

    @Scheduled(cron = "${naqqa.analytics.rollup-cron:0 20 0 * * *}")
    public void nightly() {
        if (!props.isEnabled()) {
            return;
        }
        try {
            String yesterday = DAY.format(Instant.now().minus(1, ChronoUnit.DAYS));
            rollupDay(yesterday);
        } catch (Exception e) {
            log.warn("[analytics] rollup failed: {}", e.getMessage());
        }
        prune();
    }

    /** Aggregate one UTC day's page views into per-entity daily rollups (upsert). */
    public void rollupDay(String day) {
        Aggregation agg = newAggregation(
                match(Criteria.where("day").is(day).and("eventType").is("pageview")),
                group(fields("property", "entityType", "entityId"))
                        .count().as("views")
                        .addToSet("visitorId").as("visitors")
                        .addToSet("sessionId").as("sessions"),
                project("views")
                        .and("visitors").size().as("visitors")
                        .and("sessions").size().as("sessions")
                        .and("_id.property").as("property")
                        .and("_id.entityType").as("entityType")
                        .and("_id.entityId").as("entityId"));
        AggregationResults<Document> r = mongo.aggregate(agg, COLL, Document.class);
        int n = 0;
        for (Document d : r.getMappedResults()) {
            String property = String.valueOf(d.get("property"));
            String entityType = String.valueOf(d.get("entityType"));
            String entityId = String.valueOf(d.get("entityId"));
            AnalyticsDailyRollupEntity e = new AnalyticsDailyRollupEntity();
            e.setId(property + "|" + entityType + "|" + entityId + "|" + day);
            e.setProperty(property);
            e.setEntityType(entityType);
            e.setEntityId(entityId);
            e.setDay(day);
            e.setViews(((Number) d.getOrDefault("views", 0)).longValue());
            e.setVisitors(((Number) d.getOrDefault("visitors", 0)).longValue());
            e.setSessions(((Number) d.getOrDefault("sessions", 0)).longValue());
            rollups.save(e);
            n++;
        }
        log.info("[analytics] rolled up {} entity-days for {}.", n, day);
    }

    private void prune() {
        int days = props.getRawRetentionDays();
        if (days <= 0) {
            return;
        }
        try {
            Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
            long deleted = events.deleteByTimestampBefore(cutoff);
            if (deleted > 0) {
                log.info("[analytics] pruned {} raw events older than {} days.", deleted, days);
            }
        } catch (Exception e) {
            log.warn("[analytics] prune failed: {}", e.getMessage());
        }
    }
}
