package com.naqqa.analytics.collect;

import com.naqqa.analytics.model.AnalyticsEvent;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RealtimeCounters {

    private static final Duration TTL = Duration.ofDays(3);

    private final KeyValueStore store;
    private final String prefix;

    public RealtimeCounters(KeyValueStore store, String prefix) {
        this.store = store;
        this.prefix = prefix;
    }

    public void record(List<AnalyticsEvent> events) {
        Map<String, Set<String>> keys = new LinkedHashMap<>();
        for (AnalyticsEvent e : events) {
            if (e.isBot() || e.isInternal() || e.isTest() || e.getVid() == null || e.getDay() == null) {
                continue;
            }
            keys.computeIfAbsent(dayKey(e.getDay()), k -> new LinkedHashSet<>()).add(e.getVid());
            if (e.entityKey() != null) {
                keys.computeIfAbsent(entityKey(e.getDay(), e.entityKey()), k -> new LinkedHashSet<>()).add(e.getVid());
            }
            if (e.getCompanyId() != null) {
                keys.computeIfAbsent(companyKey(e.getDay(), e.getCompanyId()), k -> new LinkedHashSet<>()).add(e.getVid());
            }
        }
        try {
            keys.forEach((k, v) -> store.hllAdd(k, TTL, v.toArray(String[]::new)));
        } catch (RuntimeException ignored) {
        }
    }

    public long visitors(String day) {
        return store.hllCount(dayKey(day));
    }

    public long entityVisitors(String day, String entityType, String entityId) {
        return store.hllCount(entityKey(day, entityType + ":" + entityId));
    }

    public long companyVisitors(String day, String companyId) {
        return store.hllCount(companyKey(day, companyId));
    }

    private String dayKey(String day) {
        return prefix + "hll:d:" + day;
    }

    private String entityKey(String day, String entity) {
        return prefix + "hll:e:" + day + ":" + entity;
    }

    private String companyKey(String day, String companyId) {
        return prefix + "hll:c:" + day + ":" + companyId;
    }
}
