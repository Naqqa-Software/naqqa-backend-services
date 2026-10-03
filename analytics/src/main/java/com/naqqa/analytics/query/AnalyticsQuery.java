package com.naqqa.analytics.query;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public record AnalyticsQuery(LocalDate from, LocalDate to, LocalDate compareFrom, LocalDate compareTo, String granularity,
                             Map<String, String> filters, Set<String> companyIds, boolean includeBots, boolean includeInternal,
                             int page, int size, String sort, String dir, ZoneId zone, Map<String, String> params) {

    public static final String HOUR = "hour";
    public static final String DAY = "day";
    public static final String WEEK = "week";
    public static final String MONTH = "month";

    public AnalyticsQuery {
        filters = filters == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(filters));
        companyIds = companyIds == null ? null : Collections.unmodifiableSet(new TreeSet<>(companyIds));
        params = params == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(params));
        granularity = granularity == null ? DAY : granularity;
        zone = zone == null ? ZoneId.of("UTC") : zone;
    }

    public static AnalyticsQuery range(LocalDate from, LocalDate to, ZoneId zone) {
        return new AnalyticsQuery(from, to, null, null, DAY, Map.of(), null, false, false, 0, 50, null, null, zone, Map.of());
    }

    public Instant start() {
        return from.atStartOfDay(zone).toInstant();
    }

    public Instant endExclusive() {
        return to.plusDays(1).atStartOfDay(zone).toInstant();
    }

    public boolean hasCompare() {
        return compareFrom != null && compareTo != null;
    }

    public boolean scoped() {
        return companyIds != null;
    }

    public String filter(String key) {
        return filters.get(key);
    }

    public String param(String key) {
        return params.get(key);
    }

    public AnalyticsQuery withRange(LocalDate f, LocalDate t) {
        return new AnalyticsQuery(f, t, null, null, granularity, filters, companyIds, includeBots, includeInternal, page, size, sort, dir,
                zone, params);
    }

    public AnalyticsQuery compareQuery() {
        return hasCompare() ? withRange(compareFrom, compareTo) : null;
    }

    public AnalyticsQuery withCompanyIds(Set<String> ids) {
        return new AnalyticsQuery(from, to, compareFrom, compareTo, granularity, filters, ids, includeBots, includeInternal, page, size,
                sort, dir, zone, params);
    }

    public AnalyticsQuery withFilters(Map<String, String> f) {
        return new AnalyticsQuery(from, to, compareFrom, compareTo, granularity, f, companyIds, includeBots, includeInternal, page, size,
                sort, dir, zone, params);
    }

    public AnalyticsQuery withoutFilter(String key) {
        Map<String, String> f = new LinkedHashMap<>(filters);
        f.remove(key);
        return withFilters(f);
    }

    public String cacheKey() {
        return from + "|" + to + "|" + compareFrom + "|" + compareTo + "|" + granularity + "|" + new TreeMap<>(filters) + "|" + companyIds
                + "|" + includeBots + "|" + includeInternal + "|" + page + "|" + size + "|" + sort + "|" + dir + "|" + new TreeMap<>(params);
    }
}
