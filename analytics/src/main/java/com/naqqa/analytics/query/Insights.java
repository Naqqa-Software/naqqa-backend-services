package com.naqqa.analytics.query;

import com.naqqa.analytics.collect.CookielessHasher;
import com.naqqa.analytics.model.AnalyticsEvent;
import com.naqqa.analytics.query.AnalyticsDtos.BenchMetrics;
import com.naqqa.analytics.query.AnalyticsDtos.BenchRow;
import com.naqqa.analytics.query.AnalyticsDtos.Cohort;
import com.naqqa.analytics.query.AnalyticsDtos.FunnelStep;
import com.naqqa.analytics.query.AnalyticsDtos.HeatCell;
import com.naqqa.analytics.query.AnalyticsDtos.KeyCount;
import com.naqqa.analytics.query.AnalyticsDtos.PartnerKpis;
import com.naqqa.analytics.query.AnalyticsDtos.PartnerSearchRow;
import com.naqqa.analytics.query.AnalyticsDtos.PartnerSearches;
import com.naqqa.analytics.query.AnalyticsDtos.PartnerSeriesPoint;
import com.naqqa.analytics.query.AnalyticsDtos.PartnerZeroRow;
import com.naqqa.analytics.query.AnalyticsDtos.SearchRow;
import com.naqqa.analytics.query.AnalyticsDtos.SearchTotals;
import com.naqqa.analytics.query.AnalyticsDtos.Seg;
import com.naqqa.analytics.query.AnalyticsDtos.ZeroRow;
import com.naqqa.analytics.query.EventSource.Group;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

public final class Insights {

    public static final String SCOPE_SESSION = "session";
    public static final String SCOPE_VISITOR = "visitor";

    private Insights() {
    }

    public record SearchResult(SearchTotals totals, List<SearchRow> top, List<ZeroRow> zeroResults, List<KeyCount> filters,
                               List<KeyCount> sorts) {
    }

    public static SearchResult search(List<AnalyticsEvent> events, int limit) {
        Map<String, long[]> perQuery = new LinkedHashMap<>();
        Map<String, Set<String>> visitors = new HashMap<>();
        Map<String, Set<String>> clickedPairs = new HashMap<>();
        Map<String, Long> filters = new LinkedHashMap<>();
        Map<String, Long> sorts = new LinkedHashMap<>();
        Map<String, String> lastQueryBySid = new HashMap<>();
        Set<String> searchPairs = new HashSet<>();
        long searches = 0;
        long zero = 0;
        long clicks = 0;
        for (AnalyticsEvent e : events) {
            switch (e.getName()) {
                case "search" -> {
                    String q = e.stringProp("q");
                    if (q == null) {
                        continue;
                    }
                    searches++;
                    long[] s = perQuery.computeIfAbsent(q, k -> new long[5]);
                    s[0]++;
                    Long results = e.longProp("results");
                    if (results != null) {
                        s[1] += results;
                        s[2]++;
                    }
                    boolean isZero = e.boolProp("zero") || results != null && results == 0;
                    if (isZero) {
                        s[3]++;
                        zero++;
                    }
                    if (e.getVid() != null) {
                        visitors.computeIfAbsent(q, k -> new HashSet<>()).add(e.getVid());
                    }
                    if (e.getSid() != null) {
                        lastQueryBySid.put(e.getSid(), q);
                        searchPairs.add(e.getSid() + "\u0000" + q);
                    }
                }
                case "search_result_click" -> {
                    clicks++;
                    String q = e.stringProp("q");
                    if (q == null && e.getSid() != null) {
                        q = lastQueryBySid.get(e.getSid());
                    }
                    if (q != null) {
                        perQuery.computeIfAbsent(q, k -> new long[5])[4]++;
                        if (e.getSid() != null) {
                            clickedPairs.computeIfAbsent(q, k -> new HashSet<>()).add(e.getSid());
                        }
                    }
                }
                case "filter_apply" -> {
                    if (e.prop("filters") instanceof Map<?, ?> m) {
                        for (Object k : m.keySet()) {
                            filters.merge(String.valueOf(k), 1L, Long::sum);
                        }
                    }
                }
                case "sort_change" -> {
                    String s = e.stringProp("sort");
                    if (s != null) {
                        sorts.merge(s, 1L, Long::sum);
                    }
                }
                default -> {
                }
            }
        }
        long clickedSearchSessions = 0;
        for (String pair : searchPairs) {
            String[] p = pair.split("\u0000", 2);
            if (clickedPairs.getOrDefault(p[1], Set.of()).contains(p[0])) {
                clickedSearchSessions++;
            }
        }
        List<SearchRow> top = new ArrayList<>();
        List<ZeroRow> zeroRows = new ArrayList<>();
        perQuery.forEach((q, s) -> {
            if (s[0] == 0) {
                return;
            }
            long v = visitors.getOrDefault(q, Set.of()).size();
            long clickedSessions = clickedPairs.getOrDefault(q, Set.of()).size();
            long searchSessions = searchPairs.stream().filter(p -> p.endsWith("\u0000" + q)).count();
            top.add(new SearchRow(q, s[0], v, s[2] == 0 ? 0.0 : Reports.round2((double) s[1] / s[2]), s[4], Reports.ratio(clickedSessions, searchSessions)));
            if (s[3] > 0) {
                zeroRows.add(new ZeroRow(q, s[3], v));
            }
        });
        top.sort(Comparator.comparingLong(SearchRow::searches).reversed().thenComparing(SearchRow::q));
        zeroRows.sort(Comparator.comparingLong(ZeroRow::searches).reversed().thenComparing(ZeroRow::q));
        SearchTotals totals = new SearchTotals(searches, perQuery.values().stream().filter(s -> s[0] > 0).count(), zero,
                Reports.ratio(zero, searches), clicks, Reports.ratio(clickedSearchSessions, searchPairs.size()));
        return new SearchResult(totals, Reports.limit(top, limit), Reports.limit(zeroRows, limit), Reports.keyCounts(filters, limit),
                Reports.keyCounts(sorts, limit));
    }

    public static List<FunnelStep> funnel(List<AnalyticsEvent> events, List<String> steps, String scope) {
        Map<String, List<AnalyticsEvent>> units = new LinkedHashMap<>();
        boolean visitor = SCOPE_VISITOR.equals(scope);
        for (AnalyticsEvent e : events) {
            String unit = visitor ? e.getVid() : e.getSid();
            if (unit != null) {
                units.computeIfAbsent(unit, k -> new ArrayList<>()).add(e);
            }
        }
        long[] reached = new long[steps.size()];
        for (List<AnalyticsEvent> list : units.values()) {
            list.sort(Comparator.comparing(AnalyticsEvent::getTs));
            int idx = 0;
            for (AnalyticsEvent e : list) {
                if (idx >= steps.size()) {
                    break;
                }
                if (stepMatches(steps.get(idx), e)) {
                    reached[idx]++;
                    idx++;
                }
            }
        }
        List<FunnelStep> out = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            long prev = i == 0 ? reached[0] : reached[i - 1];
            double rate = Reports.ratio(reached[i], reached[0]);
            double drop = i == 0 ? 0.0 : Reports.round4(1.0 - Reports.ratio(reached[i], prev));
            if (i > 0 && prev == 0) {
                drop = 0.0;
            }
            out.add(new FunnelStep(steps.get(i), reached[i], i == 0 ? (reached[0] > 0 ? 1.0 : 0.0) : rate, drop));
        }
        return out;
    }

    static boolean stepMatches(String step, AnalyticsEvent e) {
        int colon = step.indexOf(':');
        if (colon < 0) {
            return step.equals(e.getName());
        }
        return step.substring(0, colon).equals(e.getName()) && step.substring(colon + 1).equals(e.getEntityType());
    }

    public static List<Cohort> cohorts(List<AnalyticsEvent> events, LocalDate from, LocalDate to, int weeks, ZoneId zone) {
        Map<String, LocalDate> firstWeek = new HashMap<>();
        Map<String, Set<LocalDate>> activeWeeks = new HashMap<>();
        for (AnalyticsEvent e : events) {
            String vid = e.getVid();
            if (vid == null || CookielessHasher.isCookieless(vid)) {
                continue;
            }
            LocalDate week = LocalDateTime.ofInstant(e.getTs(), zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            firstWeek.merge(vid, week, (a, b) -> a.isBefore(b) ? a : b);
            activeWeeks.computeIfAbsent(vid, k -> new HashSet<>()).add(week);
        }
        TreeMap<LocalDate, List<String>> cohorts = new TreeMap<>();
        firstWeek.forEach((vid, w) -> cohorts.computeIfAbsent(w, k -> new ArrayList<>()).add(vid));
        LocalDate lastWeek = to.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        List<Cohort> out = new ArrayList<>();
        for (Map.Entry<LocalDate, List<String>> c : cohorts.entrySet()) {
            LocalDate start = c.getKey();
            long span = Math.min(weeks, ChronoUnit.WEEKS.between(start, lastWeek) + 1);
            List<Long> counts = new ArrayList<>();
            List<Double> retention = new ArrayList<>();
            for (int i = 0; i < span; i++) {
                LocalDate w = start.plusWeeks(i);
                long n = 0;
                for (String vid : c.getValue()) {
                    if (activeWeeks.get(vid).contains(w)) {
                        n++;
                    }
                }
                counts.add(n);
                retention.add(Reports.ratio(n, c.getValue().size()));
            }
            out.add(new Cohort(start.toString(), c.getValue().size(), counts, retention));
        }
        return out;
    }

    public static List<Seg> segments(List<AnalyticsEvent> events, Function<AnalyticsEvent, String> key, int k, int limit) {
        Map<String, Set<String>> m = new LinkedHashMap<>();
        Set<String> all = new HashSet<>();
        for (AnalyticsEvent e : events) {
            if (e.getVid() == null) {
                continue;
            }
            String v = key.apply(e);
            m.computeIfAbsent(v == null ? "(unknown)" : v, x -> new HashSet<>()).add(e.getVid());
            all.add(e.getVid());
        }
        List<Seg> out = new ArrayList<>();
        m.forEach((s, v) -> out.add(new Seg(s, (long) v.size(), Reports.ratio(v.size(), all.size()), false)));
        out.sort(Comparator.comparing(Seg::visitors).reversed().thenComparing(Seg::key));
        List<Seg> limited = Reports.limit(out, limit);
        return k > 0 ? kAnonymize(limited, k) : limited;
    }

    public static List<Seg> kAnonymize(List<Seg> segments, int k) {
        List<Seg> out = new ArrayList<>();
        boolean suppressed = false;
        for (Seg s : segments) {
            if (s.visitors() != null && s.visitors() < k) {
                suppressed = true;
            } else {
                out.add(s);
            }
        }
        if (suppressed) {
            out.add(new Seg(null, null, null, true));
        }
        return out;
    }

    public static List<HeatCell> heatmap(List<AnalyticsEvent> events, ZoneId zone, int k) {
        Map<Integer, Set<String>> visitors = new HashMap<>();
        Map<Integer, Long> counts = new HashMap<>();
        for (AnalyticsEvent e : events) {
            LocalDateTime t = LocalDateTime.ofInstant(e.getTs(), zone);
            int cell = t.getDayOfWeek().getValue() * 100 + t.getHour();
            counts.merge(cell, 1L, Long::sum);
            if (e.getVid() != null) {
                visitors.computeIfAbsent(cell, x -> new HashSet<>()).add(e.getVid());
            }
        }
        List<HeatCell> out = new ArrayList<>();
        for (int d = 1; d <= 7; d++) {
            for (int h = 0; h < 24; h++) {
                int cell = d * 100 + h;
                long v = visitors.getOrDefault(cell, Set.of()).size();
                long n = counts.getOrDefault(cell, 0L);
                if (k > 0 && v > 0 && v < k) {
                    v = 0;
                    n = 0;
                }
                out.add(new HeatCell(d, h, v, n));
            }
        }
        return out;
    }

    public record RealtimeResult(long activeVisitors, long views, List<AnalyticsDtos.RealtimePage> pages, List<AnalyticsDtos.RealtimePath> topPaths,
                                 List<KeyCount> byCountry, List<AnalyticsDtos.MinutePoint> perMinute, List<AnalyticsDtos.RealtimeEvent> events) {
    }

    public static RealtimeResult realtime(List<AnalyticsEvent> recent, long now, int windowMinutes, int k) {
        long minuteNow = now / 60_000L * 60_000L;
        long firstMinute = minuteNow - (windowMinutes - 1L) * 60_000L;
        long since = now - windowMinutes * 60_000L;
        Set<String> active = new HashSet<>();
        long views = 0;
        Map<String, long[]> pathViews = new LinkedHashMap<>();
        Map<String, Set<String>> pathVisitors = new HashMap<>();
        Map<String, Set<String>> countries = new HashMap<>();
        Map<Long, long[]> minuteViews = new LinkedHashMap<>();
        Map<Long, Set<String>> minuteVisitors = new HashMap<>();
        for (long m = firstMinute; m <= minuteNow; m += 60_000L) {
            minuteViews.put(m, new long[1]);
        }
        List<AnalyticsEvent> sorted = new ArrayList<>(recent);
        sorted.sort(Comparator.comparing(AnalyticsEvent::getTs).reversed());
        List<AnalyticsDtos.RealtimeEvent> feed = new ArrayList<>();
        for (AnalyticsEvent e : sorted) {
            long ts = e.epochMs();
            if (ts < since || ts > now) {
                continue;
            }
            long minute = ts / 60_000L * 60_000L;
            boolean pv = "page_view".equals(e.getName());
            if (e.getVid() != null) {
                active.add(e.getVid());
                countries.computeIfAbsent(e.getCountry() == null ? "(unknown)" : e.getCountry(), x -> new HashSet<>()).add(e.getVid());
                if (minuteViews.containsKey(minute)) {
                    minuteVisitors.computeIfAbsent(minute, x -> new HashSet<>()).add(e.getVid());
                }
                if (e.getPath() != null) {
                    pathVisitors.computeIfAbsent(e.getPath(), x -> new HashSet<>()).add(e.getVid());
                }
            }
            if (pv) {
                views++;
                if (e.getPath() != null) {
                    pathViews.computeIfAbsent(e.getPath(), x -> new long[1])[0]++;
                }
                long[] mv = minuteViews.get(minute);
                if (mv != null) {
                    mv[0]++;
                }
            }
            if (feed.size() < 50 && !"item_impression".equals(e.getName())) {
                feed.add(new AnalyticsDtos.RealtimeEvent(ts, e.getName(), e.getPath(), e.getEntityType(), e.getEntityId()));
            }
        }
        List<AnalyticsDtos.RealtimePage> pages = new ArrayList<>();
        pathVisitors.forEach((p, v) -> pages.add(new AnalyticsDtos.RealtimePage(p, v.size())));
        pages.sort(Comparator.comparingLong(AnalyticsDtos.RealtimePage::visitors).reversed().thenComparing(AnalyticsDtos.RealtimePage::path));
        List<AnalyticsDtos.RealtimePath> top = new ArrayList<>();
        pathViews.forEach((p, v) -> top.add(new AnalyticsDtos.RealtimePath(p, v[0], pathVisitors.getOrDefault(p, Set.of()).size())));
        top.sort(Comparator.comparingLong(AnalyticsDtos.RealtimePath::views).reversed().thenComparing(AnalyticsDtos.RealtimePath::path));
        Map<String, Long> byCountry = new LinkedHashMap<>();
        countries.forEach((c, v) -> byCountry.put(c, (long) v.size()));
        List<AnalyticsDtos.MinutePoint> perMinute = new ArrayList<>();
        minuteViews.forEach((m, v) -> perMinute.add(new AnalyticsDtos.MinutePoint(m, v[0], minuteVisitors.getOrDefault(m, Set.of()).size())));
        long activeCount = active.size();
        if (k > 0 && activeCount > 0 && activeCount < k) {
            return new RealtimeResult(0, 0, List.of(), List.of(), List.of(), perMinute.stream()
                    .map(p -> new AnalyticsDtos.MinutePoint(p.t(), 0, 0)).toList(), List.of());
        }
        return new RealtimeResult(activeCount, views, Reports.limit(pages, 20), Reports.limit(top, 20), Reports.keyCounts(byCountry, 20),
                perMinute, k > 0 ? List.of() : feed);
    }

    public static List<AnalyticsDtos.TimeseriesPoint> timeseries(List<AnalyticsEvent> events, AnalyticsQuery q, String name) {
        Map<String, long[]> counts = new LinkedHashMap<>();
        Map<String, Set<String>> visitors = new HashMap<>();
        Map<String, Set<String>> sessions = new HashMap<>();
        for (String k : Buckets.range(q.from(), q.to(), q.granularity())) {
            counts.put(k, new long[2]);
        }
        for (AnalyticsEvent e : events) {
            String k = Buckets.key(e.getTs(), q.granularity(), q.zone());
            long[] c = counts.get(k);
            if (c == null) {
                continue;
            }
            if ("page_view".equals(e.getName())) {
                c[0]++;
            }
            if (name == null ? !"item_impression".equals(e.getName()) : name.equals(e.getName())) {
                c[1]++;
            }
            if (e.getVid() != null) {
                visitors.computeIfAbsent(k, x -> new HashSet<>()).add(e.getVid());
            }
            if (e.getSid() != null) {
                sessions.computeIfAbsent(k, x -> new HashSet<>()).add(e.getSid());
            }
        }
        List<AnalyticsDtos.TimeseriesPoint> out = new ArrayList<>();
        counts.forEach((k, c) -> out.add(new AnalyticsDtos.TimeseriesPoint(k, visitors.getOrDefault(k, Set.of()).size(), c[0],
                sessions.getOrDefault(k, Set.of()).size(), c[1])));
        return out;
    }

    public static List<AnalyticsDtos.EventCount> eventCounts(List<AnalyticsEvent> events, long impressions, long impressionVisitors) {
        Map<String, long[]> counts = new LinkedHashMap<>();
        Map<String, Set<String>> visitors = new HashMap<>();
        for (AnalyticsEvent e : events) {
            counts.computeIfAbsent(e.getName(), x -> new long[1])[0]++;
            if (e.getVid() != null) {
                visitors.computeIfAbsent(e.getName(), x -> new HashSet<>()).add(e.getVid());
            }
        }
        List<AnalyticsDtos.EventCount> out = new ArrayList<>();
        counts.forEach((n, c) -> out.add(new AnalyticsDtos.EventCount(n, c[0], visitors.getOrDefault(n, Set.of()).size())));
        if (impressions > 0) {
            out.add(new AnalyticsDtos.EventCount("item_impression", impressions, impressionVisitors));
        }
        out.sort(Comparator.comparingLong(AnalyticsDtos.EventCount::count).reversed().thenComparing(AnalyticsDtos.EventCount::name));
        return out;
    }

    public static PartnerKpis partnerKpis(List<AnalyticsEvent> events, List<Group> impressions) {
        long companyViews = 0;
        long clicks = 0;
        long views = 0;
        long activeSum = 0;
        long activeCount = 0;
        long reviews = 0;
        long ratingSum = 0;
        long ratingCount = 0;
        Set<String> visitors = new HashSet<>();
        Set<String> uniques = new HashSet<>();
        for (AnalyticsEvent e : events) {
            if ("item_impression".equals(e.getName())) {
                continue;
            }
            if (e.getVid() != null) {
                visitors.add(e.getVid());
            }
            switch (e.getName()) {
                case "company_view" -> companyViews++;
                case "item_click" -> clicks++;
                case "item_view", "booklet_open" -> {
                    views++;
                    if (e.getVid() != null) {
                        uniques.add(e.getVid());
                    }
                    Long ms = e.longProp("activeMs");
                    if (ms != null) {
                        activeSum += ms;
                        activeCount++;
                    }
                }
                case "review_submit" -> {
                    reviews++;
                    Long r = e.longProp("rating");
                    if (r != null && r > 0) {
                        ratingSum += r;
                        ratingCount++;
                    }
                }
                default -> {
                }
            }
        }
        long impressionsCount = Reports.sumGroups(impressions);
        long reach = 0;
        for (Group g : impressions) {
            reach = Math.max(reach, g.uniques());
        }
        return new PartnerKpis(companyViews, visitors.size(), reach, impressionsCount, clicks, Reports.ratio(clicks, impressionsCount), views,
                uniques.size(), Reports.avg(activeSum, activeCount), Reports.conversions(events), reviews,
                ratingCount == 0 ? null : Reports.round2((double) ratingSum / ratingCount));
    }

    public static List<PartnerSeriesPoint> partnerSeries(List<AnalyticsEvent> events, List<Group> impressionsByDay, AnalyticsQuery q) {
        Map<String, long[]> m = new LinkedHashMap<>();
        Map<String, Set<String>> v = new HashMap<>();
        for (String k : Buckets.range(q.from(), q.to(), q.granularity())) {
            m.put(k, new long[3]);
        }
        for (Group g : impressionsByDay) {
            String day = g.get("day");
            if (day == null) {
                continue;
            }
            String k = Buckets.key(LocalDate.parse(day).atStartOfDay(q.zone()).toInstant(), q.granularity(), q.zone());
            long[] a = m.get(k);
            if (a != null) {
                a[0] += g.count();
            }
        }
        for (AnalyticsEvent e : events) {
            String k = Buckets.key(e.getTs(), q.granularity(), q.zone());
            long[] a = m.get(k);
            if (a == null || "item_impression".equals(e.getName())) {
                continue;
            }
            if ("item_click".equals(e.getName())) {
                a[1]++;
            } else if ("item_view".equals(e.getName()) || "booklet_open".equals(e.getName()) || "company_view".equals(e.getName())) {
                a[2]++;
            }
            if (e.getVid() != null) {
                v.computeIfAbsent(k, x -> new HashSet<>()).add(e.getVid());
            }
        }
        List<PartnerSeriesPoint> out = new ArrayList<>();
        m.forEach((k, a) -> out.add(new PartnerSeriesPoint(k, a[0], a[1], a[2], v.getOrDefault(k, Set.of()).size())));
        return out;
    }

    public static PartnerSearches partnerSearches(AnalyticsDtos.Meta meta, List<AnalyticsEvent> searchEvents, Set<String> companyIds,
                                                  Set<String> categoryIds, int k, int limit) {
        Map<String, Set<String>> ownSessions = new HashMap<>();
        for (AnalyticsEvent e : searchEvents) {
            if ("search_result_click".equals(e.getName()) && e.getCompanyId() != null && companyIds.contains(e.getCompanyId()) && e.getSid() != null) {
                ownSessions.computeIfAbsent(e.getSid(), x -> new HashSet<>());
            }
        }
        Map<String, long[]> top = new LinkedHashMap<>();
        Map<String, Set<String>> topVisitors = new HashMap<>();
        Map<String, long[]> zero = new LinkedHashMap<>();
        Map<String, Set<String>> zeroVisitors = new HashMap<>();
        for (AnalyticsEvent e : searchEvents) {
            String q = e.stringProp("q");
            if (q == null) {
                continue;
            }
            if ("search".equals(e.getName())) {
                if (e.getSid() != null && ownSessions.containsKey(e.getSid())) {
                    top.computeIfAbsent(q, x -> new long[2])[0]++;
                    if (e.getVid() != null) {
                        topVisitors.computeIfAbsent(q, x -> new HashSet<>()).add(e.getVid());
                    }
                }
                Long results = e.longProp("results");
                boolean isZero = e.boolProp("zero") || results != null && results == 0;
                if (isZero && e.getCategoryId() != null && categoryIds.contains(e.getCategoryId())) {
                    zero.computeIfAbsent(q, x -> new long[1])[0]++;
                    if (e.getVid() != null) {
                        zeroVisitors.computeIfAbsent(q, x -> new HashSet<>()).add(e.getVid());
                    }
                }
            } else if ("search_result_click".equals(e.getName()) && e.getCompanyId() != null && companyIds.contains(e.getCompanyId())) {
                top.computeIfAbsent(q, x -> new long[2])[1]++;
            }
        }
        long suppressed = 0;
        List<PartnerSearchRow> topRows = new ArrayList<>();
        for (Map.Entry<String, long[]> en : top.entrySet()) {
            if (topVisitors.getOrDefault(en.getKey(), Set.of()).size() < k || en.getValue()[0] == 0) {
                suppressed++;
                continue;
            }
            topRows.add(new PartnerSearchRow(en.getKey(), en.getValue()[0], en.getValue()[1]));
        }
        List<PartnerZeroRow> zeroRows = new ArrayList<>();
        for (Map.Entry<String, long[]> en : zero.entrySet()) {
            if (zeroVisitors.getOrDefault(en.getKey(), Set.of()).size() < k) {
                suppressed++;
                continue;
            }
            zeroRows.add(new PartnerZeroRow(en.getKey(), en.getValue()[0]));
        }
        topRows.sort(Comparator.comparingLong(PartnerSearchRow::searches).reversed().thenComparing(PartnerSearchRow::q));
        zeroRows.sort(Comparator.comparingLong(PartnerZeroRow::searches).reversed().thenComparing(PartnerZeroRow::q));
        return new PartnerSearches(meta, Reports.limit(topRows, limit), Reports.limit(zeroRows, limit), suppressed);
    }

    public static List<BenchRow> benchmark(List<AnalyticsEvent> events, List<Group> impressions, Set<String> ownCompanies,
                                           Collection<String> categories, int minPartners) {
        Map<String, Map<String, long[]>> stats = new LinkedHashMap<>();
        Map<String, Map<String, Set<String>>> items = new HashMap<>();
        for (Group g : impressions) {
            String cat = g.get("categoryId");
            String company = g.get("companyId");
            if (cat == null || company == null || !categories.contains(cat)) {
                continue;
            }
            stats.computeIfAbsent(cat, x -> new LinkedHashMap<>()).computeIfAbsent(company, x -> new long[6])[0] += g.count();
        }
        for (AnalyticsEvent e : events) {
            String cat = e.getCategoryId();
            String company = e.getCompanyId();
            if (cat == null || company == null || !categories.contains(cat)) {
                continue;
            }
            long[] s = stats.computeIfAbsent(cat, x -> new LinkedHashMap<>()).computeIfAbsent(company, x -> new long[6]);
            if (e.entityKey() != null) {
                items.computeIfAbsent(cat, x -> new HashMap<>()).computeIfAbsent(company, x -> new HashSet<>()).add(e.entityKey());
            }
            switch (e.getName()) {
                case "item_click" -> s[1]++;
                case "item_view" -> {
                    s[2]++;
                    Long ms = e.longProp("activeMs");
                    if (ms != null) {
                        s[3] += ms;
                        s[4]++;
                    }
                }
                default -> {
                    if (SessionSummary.CONVERSIONS.contains(e.getName())) {
                        s[5]++;
                    }
                }
            }
        }
        List<BenchRow> out = new ArrayList<>();
        for (String cat : categories) {
            Map<String, long[]> perCompany = stats.getOrDefault(cat, Map.of());
            long partners = perCompany.size();
            long[] own = new long[6];
            Set<String> ownItems = new HashSet<>();
            for (String c : ownCompanies) {
                long[] s = perCompany.get(c);
                if (s != null) {
                    for (int i = 0; i < 6; i++) {
                        own[i] += s[i];
                    }
                }
                ownItems.addAll(items.getOrDefault(cat, Map.of()).getOrDefault(c, Set.of()));
            }
            BenchMetrics company = metrics(own, ownItems.size());
            if (partners < minPartners) {
                out.add(new BenchRow(cat, partners, true, company, null));
                continue;
            }
            double ctr = 0;
            double active = 0;
            double vpi = 0;
            double conv = 0;
            for (Map.Entry<String, long[]> en : perCompany.entrySet()) {
                BenchMetrics m = metrics(en.getValue(), items.getOrDefault(cat, Map.of()).getOrDefault(en.getKey(), Set.of()).size());
                ctr += m.ctr();
                active += m.avgActiveMs();
                vpi += m.viewsPerItem();
                conv += m.conversionRate();
            }
            BenchMetrics avg = new BenchMetrics(Reports.round4(ctr / partners), Math.round(active / partners), Reports.round2(vpi / partners),
                    Reports.round4(conv / partners));
            out.add(new BenchRow(cat, partners, false, company, avg));
        }
        return out;
    }

    private static BenchMetrics metrics(long[] s, int items) {
        return new BenchMetrics(Reports.ratio(s[1], s[0]), Reports.avg(s[3], s[4]), items == 0 ? 0.0 : Reports.round2((double) s[2] / items),
                Reports.ratio(s[5], s[2]));
    }
}
