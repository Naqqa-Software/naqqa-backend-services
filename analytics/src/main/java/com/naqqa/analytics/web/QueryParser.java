package com.naqqa.analytics.web;

import com.naqqa.analytics.query.AnalyticsQuery;
import com.naqqa.analytics.query.EventFilter;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class QueryParser {

    public static final Set<String> GRANULARITIES = Set.of(AnalyticsQuery.HOUR, AnalyticsQuery.DAY, AnalyticsQuery.WEEK, AnalyticsQuery.MONTH);
    public static final Set<String> EXTRA = Set.of("flowBy", "steps", "scope", "weeks", "report", "format", "section", "limit", "name", "q");
    private static final int MAX_HOUR_DAYS = 31;
    private static final int MAX_SIZE = 500;

    private QueryParser() {
    }

    public static AnalyticsQuery parse(Map<String, String> params, ZoneId zone, Clock clock, int maxRangeDays, boolean admin) {
        Map<String, String> p = params == null ? Map.of() : params;
        LocalDate today = LocalDate.now(clock.withZone(zone));
        LocalDate to = date(p.get("to"), today);
        LocalDate from = date(p.get("from"), to.minusDays(29));
        if (from.isAfter(to)) {
            throw AnalyticsException.badRequest("from must not be after to");
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > maxRangeDays) {
            throw new AnalyticsException(400, AnalyticsException.RANGE_TOO_LARGE, "Range exceeds " + maxRangeDays + " days");
        }
        LocalDate compareFrom = date(p.get("compareFrom"), null);
        LocalDate compareTo = date(p.get("compareTo"), null);
        String compare = p.get("compare");
        if (compareFrom == null && compareTo == null && compare != null) {
            if ("previous".equals(compare)) {
                compareTo = from.minusDays(1);
                compareFrom = compareTo.minusDays(days - 1);
            } else if ("year".equals(compare)) {
                compareFrom = from.minusYears(1);
                compareTo = to.minusYears(1);
            }
        }
        if (compareFrom == null != (compareTo == null)) {
            throw AnalyticsException.badRequest("compareFrom and compareTo must be set together");
        }
        if (compareFrom != null && (compareFrom.isAfter(compareTo) || ChronoUnit.DAYS.between(compareFrom, compareTo) + 1 > maxRangeDays)) {
            throw AnalyticsException.badRequest("Invalid compare range");
        }
        String granularity = p.getOrDefault("granularity", AnalyticsQuery.DAY);
        if (!GRANULARITIES.contains(granularity)) {
            throw AnalyticsException.badRequest("Invalid granularity");
        }
        if (AnalyticsQuery.HOUR.equals(granularity) && days > MAX_HOUR_DAYS) {
            granularity = AnalyticsQuery.DAY;
        }
        Map<String, String> filters = new LinkedHashMap<>();
        for (String key : EventFilter.FIELDS.keySet()) {
            String v = p.get(key);
            if (v != null && !v.isBlank()) {
                if (v.length() > 200) {
                    throw AnalyticsException.badRequest("Filter too long: " + key);
                }
                filters.put(key, v.trim());
            }
        }
        Map<String, String> extra = new LinkedHashMap<>();
        for (String key : EXTRA) {
            String v = p.get(key);
            if (v != null && !v.isBlank()) {
                extra.put(key, v.length() > 500 ? v.substring(0, 500) : v.trim());
            }
        }
        int page = intParam(p.get("page"), 0, 0, 100_000);
        int size = intParam(p.get("size"), 50, 1, MAX_SIZE);
        String dir = "asc".equalsIgnoreCase(p.get("dir")) ? "asc" : "desc";
        boolean includeBots = admin && Boolean.parseBoolean(p.get("includeBots"));
        boolean includeInternal = admin && Boolean.parseBoolean(p.get("includeInternal"));
        return new AnalyticsQuery(from, to, compareFrom, compareTo, granularity, filters, null, includeBots, includeInternal, page, size,
                p.get("sort"), dir, zone, extra);
    }

    private static LocalDate date(String v, LocalDate fallback) {
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(v.trim());
        } catch (DateTimeParseException e) {
            throw AnalyticsException.badRequest("Invalid date: " + v);
        }
    }

    private static int intParam(String v, int fallback, int min, int max) {
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(v.trim())));
        } catch (NumberFormatException e) {
            throw AnalyticsException.badRequest("Invalid number: " + v);
        }
    }
}
