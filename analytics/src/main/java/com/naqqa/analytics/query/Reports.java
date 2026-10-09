package com.naqqa.analytics.query;

import com.naqqa.analytics.model.AnalyticsEvent;
import com.naqqa.analytics.query.AnalyticsDtos.ArticleRow;
import com.naqqa.analytics.query.AnalyticsDtos.BookletPage;
import com.naqqa.analytics.query.AnalyticsDtos.BookletRow;
import com.naqqa.analytics.query.AnalyticsDtos.ClickRow;
import com.naqqa.analytics.query.AnalyticsDtos.CompanyRow;
import com.naqqa.analytics.query.AnalyticsDtos.ContentItem;
import com.naqqa.analytics.query.AnalyticsDtos.Conversions;
import com.naqqa.analytics.query.AnalyticsDtos.ErrorRow;
import com.naqqa.analytics.query.AnalyticsDtos.Flow;
import com.naqqa.analytics.query.AnalyticsDtos.KeyCount;
import com.naqqa.analytics.query.AnalyticsDtos.Kpis;
import com.naqqa.analytics.query.AnalyticsDtos.PageRow;
import com.naqqa.analytics.query.AnalyticsDtos.Row;
import com.naqqa.analytics.query.AnalyticsDtos.ScrollBucket;
import com.naqqa.analytics.query.AnalyticsDtos.SeriesPoint;
import com.naqqa.analytics.query.AnalyticsDtos.SharedItem;
import com.naqqa.analytics.query.AnalyticsDtos.Shares;
import com.naqqa.analytics.query.AnalyticsDtos.TimeBucket;
import com.naqqa.analytics.query.AnalyticsDtos.TimeStats;
import com.naqqa.analytics.query.AnalyticsDtos.VitalsRow;
import com.naqqa.analytics.query.EventSource.Group;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public final class Reports {

    public static final Set<String> ARTICLE_TYPES = Set.of("BLOG", "RECIPE");

    private Reports() {
    }

    public static double ratio(long a, long b) {
        if (b <= 0) {
            return 0.0;
        }
        return Math.round((double) a / b * 10_000d) / 10_000d;
    }

    public static double round2(double v) {
        return Math.round(v * 100d) / 100d;
    }

    public static double round4(double v) {
        return Math.round(v * 10_000d) / 10_000d;
    }

    public static long avg(long sum, long n) {
        return n <= 0 ? 0 : Math.round((double) sum / n);
    }

    public static Conversions conversions(Collection<AnalyticsEvent> events) {
        long promo = 0;
        long share = 0;
        long list = 0;
        long contact = 0;
        long store = 0;
        for (AnalyticsEvent e : events) {
            switch (e.getName()) {
                case "promocode_click" -> promo++;
                case "share_click" -> share++;
                case "add_to_list" -> list++;
                case "store_contact_click" -> contact++;
                case "store_link_click" -> store++;
                default -> {
                }
            }
        }
        return new Conversions(promo, share, list, contact, store, promo + share + list + contact + store);
    }

    public static long visitors(Collection<AnalyticsEvent> events) {
        Set<String> v = new HashSet<>();
        for (AnalyticsEvent e : events) {
            if (e.getVid() != null) {
                v.add(e.getVid());
            }
        }
        return v.size();
    }

    public static long count(Collection<AnalyticsEvent> events, String name) {
        long n = 0;
        for (AnalyticsEvent e : events) {
            if (name.equals(e.getName())) {
                n++;
            }
        }
        return n;
    }

    public static long sumGroups(List<Group> groups) {
        long n = 0;
        for (Group g : groups) {
            n += g.count();
        }
        return n;
    }

    public static Kpis kpis(List<AnalyticsEvent> events, long impressions) {
        List<SessionSummary> sessions = SessionSummary.of(events);
        Set<String> all = new HashSet<>();
        Set<String> fresh = new HashSet<>();
        Set<String> returning = new HashSet<>();
        for (AnalyticsEvent e : events) {
            if (e.getVid() == null) {
                continue;
            }
            all.add(e.getVid());
            if (Boolean.TRUE.equals(e.getNewVisitor())) {
                fresh.add(e.getVid());
            } else if (Boolean.FALSE.equals(e.getNewVisitor())) {
                returning.add(e.getVid());
            }
        }
        returning.removeAll(fresh);
        long pv = count(events, "page_view");
        long active = 0;
        long bounces = 0;
        long engaged = 0;
        long engagedViews = 0;
        long duration = 0;
        for (SessionSummary s : sessions) {
            active += s.activeMs();
            duration += s.durationMs();
            if (s.engaged()) {
                engaged++;
                engagedViews += s.pageViews();
            } else {
                bounces++;
            }
        }
        long leaveSum = 0;
        long leaveCount = 0;
        for (AnalyticsEvent e : events) {
            if ("page_leave".equals(e.getName())) {
                Long a = e.longProp("activeMs");
                if (a != null) {
                    leaveSum += a;
                    leaveCount++;
                }
            }
        }
        long clicks = count(events, "item_click");
        int n = sessions.size();
        double perSession = round2(n == 0 ? 0 : (double) pv / n);
        return new Kpis(all.size(), fresh.size(), returning.size(), fresh.size(), returning.size(), n, pv, pv, engaged, engagedViews,
                ratio(engaged, n), ratio(bounces, n), perSession, perSession, avg(active, n), avg(leaveSum, leaveCount), avg(duration, n),
                impressions, clicks, ratio(clicks, impressions), conversions(events));
    }

    public static List<SeriesPoint> series(List<AnalyticsEvent> events, AnalyticsQuery q) {
        Map<String, Set<String>> visitors = new LinkedHashMap<>();
        Map<String, Set<String>> sessions = new LinkedHashMap<>();
        Map<String, Long> pageViews = new LinkedHashMap<>();
        for (String k : Buckets.range(q.from(), q.to(), q.granularity())) {
            visitors.put(k, new HashSet<>());
            sessions.put(k, new HashSet<>());
            pageViews.put(k, 0L);
        }
        for (AnalyticsEvent e : events) {
            String k = Buckets.key(e.getTs(), q.granularity(), q.zone());
            if (!visitors.containsKey(k)) {
                continue;
            }
            if (e.getVid() != null) {
                visitors.get(k).add(e.getVid());
            }
            if (e.getSid() != null) {
                sessions.get(k).add(e.getSid());
            }
            if ("page_view".equals(e.getName())) {
                pageViews.merge(k, 1L, Long::sum);
            }
        }
        List<SeriesPoint> out = new ArrayList<>();
        for (String k : visitors.keySet()) {
            out.add(new SeriesPoint(k, visitors.get(k).size(), pageViews.get(k), sessions.get(k).size()));
        }
        return out;
    }

    public static List<Row> rows(List<SessionSummary> sessions, Function<SessionSummary, String> key, int limit) {
        Map<String, List<SessionSummary>> groups = new LinkedHashMap<>();
        for (SessionSummary s : sessions) {
            String k = key.apply(s);
            groups.computeIfAbsent(k == null ? "(none)" : k, x -> new ArrayList<>()).add(s);
        }
        List<Row> out = new ArrayList<>();
        for (Map.Entry<String, List<SessionSummary>> g : groups.entrySet()) {
            Set<String> v = new HashSet<>();
            long pv = 0;
            long active = 0;
            long bounces = 0;
            long conv = 0;
            for (SessionSummary s : g.getValue()) {
                if (s.vid() != null) {
                    v.add(s.vid());
                }
                pv += s.pageViews();
                active += s.activeMs();
                conv += s.conversions();
                if (s.bounce()) {
                    bounces++;
                }
            }
            int n = g.getValue().size();
            out.add(new Row(g.getKey(), v.size(), n, pv, ratio(bounces, n), avg(active, n), conv));
        }
        out.sort(Comparator.comparingLong(Row::sessions).reversed().thenComparing(Row::key));
        return limit > 0 && out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    public static String campaignKey(SessionSummary s) {
        if (s.campaign() == null && s.source() == null && s.medium() == null) {
            return null;
        }
        return nz(s.source()) + " / " + nz(s.medium()) + " / " + nz(s.campaign());
    }

    public static Shares shares(List<AnalyticsEvent> events, List<SessionSummary> sessions, Map<String, String> titles, int limit) {
        long shares = 0;
        long landings = 0;
        Map<String, Long> byChannel = new HashMap<>();
        Map<String, long[]> items = new LinkedHashMap<>();
        Map<String, String[]> keys = new HashMap<>();
        for (AnalyticsEvent e : events) {
            if ("share_click".equals(e.getName())) {
                shares++;
                byChannel.merge(e.stringProp("channel") == null ? "other" : e.stringProp("channel"), 1L, Long::sum);
                if (e.entityKey() != null) {
                    items.computeIfAbsent(e.entityKey(), k -> new long[2])[0]++;
                    keys.put(e.entityKey(), new String[]{e.getEntityType(), e.getEntityId()});
                }
            } else if ("share_landing".equals(e.getName())) {
                landings++;
                if (e.entityKey() != null) {
                    items.computeIfAbsent(e.entityKey(), k -> new long[2])[1]++;
                    keys.put(e.entityKey(), new String[]{e.getEntityType(), e.getEntityId()});
                }
            }
        }
        Set<String> v = new HashSet<>();
        long sessionsFromShares = 0;
        for (SessionSummary s : sessions) {
            if ("Share".equals(s.channel())) {
                sessionsFromShares++;
                if (s.vid() != null) {
                    v.add(s.vid());
                }
            }
        }
        List<SharedItem> top = new ArrayList<>();
        items.forEach((k, c) -> top.add(new SharedItem(keys.get(k)[0], keys.get(k)[1], titles == null ? null : titles.get(k), c[0], c[1])));
        top.sort(Comparator.comparingLong(SharedItem::shares).thenComparingLong(SharedItem::landings).reversed());
        return new Shares(shares, landings, v.size(), sessionsFromShares, keyCounts(byChannel, 0), limit(top, limit));
    }

    public static List<PageRow> pages(List<AnalyticsEvent> events, List<SessionSummary> sessions, int limit) {
        Map<String, long[]> stats = new LinkedHashMap<>();
        Map<String, Set<String>> visitors = new HashMap<>();
        Map<String, String> types = new HashMap<>();
        for (AnalyticsEvent e : events) {
            if (e.getPath() == null) {
                continue;
            }
            if ("page_view".equals(e.getName())) {
                stats.computeIfAbsent(e.getPath(), k -> new long[7])[0]++;
                if (e.getVid() != null) {
                    visitors.computeIfAbsent(e.getPath(), k -> new HashSet<>()).add(e.getVid());
                }
                if (e.getPageType() != null) {
                    types.putIfAbsent(e.getPath(), e.getPageType());
                }
            } else if ("page_leave".equals(e.getName())) {
                long[] s = stats.computeIfAbsent(e.getPath(), k -> new long[7]);
                Long a = e.longProp("activeMs");
                if (a != null) {
                    s[3] += a;
                    s[4]++;
                }
                Long sc = e.longProp("scroll");
                if (sc != null) {
                    s[5] += sc;
                    s[6]++;
                }
            }
        }
        for (SessionSummary s : sessions) {
            if (s.pageViews() == 0) {
                continue;
            }
            if (s.landing() != null && stats.containsKey(s.landing())) {
                stats.get(s.landing())[1]++;
            }
            if (s.exit() != null && stats.containsKey(s.exit())) {
                stats.get(s.exit())[2]++;
            }
        }
        List<PageRow> out = new ArrayList<>();
        stats.forEach((p, s) -> {
            if (s[0] > 0) {
                out.add(new PageRow(p, types.get(p), s[0], visitors.getOrDefault(p, Set.of()).size(), s[1], s[2], avg(s[3], s[4]), avg(s[5], s[6])));
            }
        });
        out.sort(Comparator.comparingLong(PageRow::views).reversed().thenComparing(PageRow::path));
        return limit(out, limit);
    }

    public static List<Flow> flows(List<SessionSummary> sessions, boolean byPath, int limit) {
        Map<String, Long> edges = new LinkedHashMap<>();
        for (SessionSummary s : sessions) {
            List<AnalyticsEvent> pages = s.pages();
            if (pages.isEmpty()) {
                continue;
            }
            String prev = "(entrance)";
            for (AnalyticsEvent p : pages) {
                String node = byPath ? p.getPath() : p.getPageType();
                if (node == null) {
                    node = "(other)";
                }
                if (!node.equals(prev)) {
                    edges.merge(prev + "\u0000" + node, 1L, Long::sum);
                    prev = node;
                }
            }
            edges.merge(prev + "\u0000(exit)", 1L, Long::sum);
        }
        List<Flow> out = new ArrayList<>();
        edges.forEach((k, v) -> {
            String[] p = k.split("\u0000", 2);
            out.add(new Flow(p[0], p[1], v));
        });
        out.sort(Comparator.comparingLong(Flow::count).reversed().thenComparing(Flow::from).thenComparing(Flow::to));
        return limit(out, limit);
    }

    public static List<ScrollBucket> scroll(List<AnalyticsEvent> events) {
        int[] marks = {25, 50, 75, 90, 100};
        Map<Integer, Set<String>> reached = new LinkedHashMap<>();
        for (int m : marks) {
            reached.put(m, new HashSet<>());
        }
        for (AnalyticsEvent e : events) {
            if (!"scroll_depth".equals(e.getName())) {
                continue;
            }
            Long pct = e.longProp("pct");
            if (pct == null) {
                continue;
            }
            for (int m : marks) {
                if (pct >= m) {
                    reached.get(m).add(e.getSid() + "|" + e.getPath());
                }
            }
        }
        List<ScrollBucket> out = new ArrayList<>();
        reached.forEach((m, s) -> out.add(new ScrollBucket(m, s.size())));
        return out;
    }

    public static TimeStats time(List<SessionSummary> sessions) {
        String[] labels = {"0-10s", "10-30s", "30-60s", "1-3m", "3-10m", "10m+"};
        long[] limits = {10_000, 30_000, 60_000, 180_000, 600_000, Long.MAX_VALUE};
        long[] counts = new long[labels.length];
        long total = 0;
        for (SessionSummary s : sessions) {
            total += s.activeMs();
            for (int i = 0; i < limits.length; i++) {
                if (s.activeMs() < limits[i]) {
                    counts[i]++;
                    break;
                }
            }
        }
        List<TimeBucket> buckets = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            buckets.add(new TimeBucket(labels[i], counts[i]));
        }
        return new TimeStats(avg(total, sessions.size()), buckets);
    }

    public static List<ClickRow> clicks(List<AnalyticsEvent> events, String name, int limit) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (AnalyticsEvent e : events) {
            if (name.equals(e.getName())) {
                m.merge(nz(e.getPath()) + "\u0000" + nz(e.stringProp("target")), 1L, Long::sum);
            }
        }
        List<ClickRow> out = new ArrayList<>();
        m.forEach((k, v) -> {
            String[] p = k.split("\u0000", 2);
            out.add(new ClickRow(p[0], p[1], v));
        });
        out.sort(Comparator.comparingLong(ClickRow::count).reversed().thenComparing(ClickRow::path));
        return limit(out, limit);
    }

    public static List<VitalsRow> vitals(List<AnalyticsEvent> events, int limit) {
        Map<String, List<double[]>> m = new LinkedHashMap<>();
        for (AnalyticsEvent e : events) {
            if ("web_vitals".equals(e.getName()) && e.getPath() != null) {
                m.computeIfAbsent(e.getPath(), k -> new ArrayList<>()).add(new double[]{
                        nn(e.doubleProp("lcp")), nn(e.doubleProp("inp")), nn(e.doubleProp("cls"))});
            }
        }
        List<VitalsRow> out = new ArrayList<>();
        m.forEach((p, list) -> out.add(new VitalsRow(p, list.size(), p75(list, 0), p75(list, 1), p75(list, 2))));
        out.sort(Comparator.comparingLong(VitalsRow::samples).reversed().thenComparing(VitalsRow::path));
        return limit(out, limit);
    }

    public static List<ErrorRow> errors(List<AnalyticsEvent> events, int limit) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (AnalyticsEvent e : events) {
            if ("js_error".equals(e.getName())) {
                m.merge(nz(e.getPath()) + "\u0000" + nz(e.stringProp("message")), 1L, Long::sum);
            }
        }
        List<ErrorRow> out = new ArrayList<>();
        m.forEach((k, v) -> {
            String[] p = k.split("\u0000", 2);
            out.add(new ErrorRow(p[0], p[1], v));
        });
        out.sort(Comparator.comparingLong(ErrorRow::count).reversed().thenComparing(ErrorRow::path));
        return limit(out, limit);
    }

    public static List<ContentItem> items(List<AnalyticsEvent> events, List<Group> impressions, String entityType,
                                          Map<String, String> titles) {
        Map<String, ItemAcc> acc = new LinkedHashMap<>();
        for (Group g : impressions) {
            String type = g.get("entityType");
            String id = g.get("entityId");
            if (type == null || id == null || entityType != null && !entityType.equals(type)) {
                continue;
            }
            ItemAcc a = acc.computeIfAbsent(type + ":" + id, k -> new ItemAcc(type, id));
            a.impressions += g.count();
            a.reach += g.uniques();
            a.positionSum += g.positionSum();
            a.positionCount += g.positionCount();
            if (a.companyId == null) {
                a.companyId = g.get("companyId");
            }
        }
        for (AnalyticsEvent e : events) {
            String key = e.entityKey();
            if (key == null || entityType != null && !entityType.equals(e.getEntityType()) || "item_impression".equals(e.getName())) {
                continue;
            }
            ItemAcc a = acc.computeIfAbsent(key, k -> new ItemAcc(e.getEntityType(), e.getEntityId()));
            if (a.companyId == null) {
                a.companyId = e.getCompanyId();
            }
            if (e.getVid() != null) {
                a.uniques.add(e.getVid());
            }
            switch (e.getName()) {
                case "item_click" -> {
                    a.clicks++;
                    a.sources.merge(e.getSourceBlock() == null ? "(none)" : e.getSourceBlock(), 1L, Long::sum);
                }
                case "item_view", "company_view" -> {
                    a.views++;
                    Long ms = e.longProp("activeMs");
                    if (ms != null) {
                        a.activeSum += ms;
                        a.activeCount++;
                    }
                }
                case "booklet_open" -> {
                    a.views++;
                    a.activeCount++;
                }
                case "booklet_page_view" -> {
                    Long ms = e.longProp("activeMs");
                    if (ms != null) {
                        a.activeSum += ms;
                    }
                }
                case "article_read" -> {
                    Long ms = e.longProp("activeMs");
                    if (ms != null) {
                        a.activeSum += ms;
                        a.activeCount++;
                    }
                }
                default -> {
                    if (SessionSummary.CONVERSIONS.contains(e.getName())) {
                        a.conversions++;
                    }
                }
            }
        }
        List<ContentItem> out = new ArrayList<>();
        for (Map.Entry<String, ItemAcc> en : acc.entrySet()) {
            ItemAcc a = en.getValue();
            out.add(new ContentItem(a.type, a.id, titles == null ? null : titles.get(en.getKey()), a.companyId, a.impressions, a.reach, a.clicks,
                    ratio(a.clicks, a.impressions), a.views, a.uniques.size(), avg(a.activeSum, a.activeCount), a.conversions,
                    a.positionCount == 0 ? null : round2((double) a.positionSum / a.positionCount), keyCounts(a.sources, 5)));
        }
        return out;
    }

    public static List<ContentItem> sortItems(List<ContentItem> items, String sort, String dir) {
        Comparator<ContentItem> c = switch (sort == null ? "impressions" : sort) {
            case "clicks" -> Comparator.comparingLong(ContentItem::clicks);
            case "ctr" -> Comparator.comparingDouble(ContentItem::ctr);
            case "views" -> Comparator.comparingLong(ContentItem::views);
            case "uniques" -> Comparator.comparingLong(ContentItem::uniques);
            case "reach" -> Comparator.comparingLong(ContentItem::reach);
            case "conversions" -> Comparator.comparingLong(ContentItem::conversions);
            case "avgActiveMs" -> Comparator.comparingLong(ContentItem::avgActiveMs);
            default -> Comparator.comparingLong(ContentItem::impressions);
        };
        if (!"asc".equalsIgnoreCase(dir)) {
            c = c.reversed();
        }
        c = c.thenComparing(ContentItem::entityType).thenComparing(ContentItem::entityId);
        List<ContentItem> out = new ArrayList<>(items);
        out.sort(c);
        return out;
    }

    public static List<BookletRow> booklets(List<AnalyticsEvent> events, Map<String, String> titles) {
        Map<String, BookletAcc> acc = new LinkedHashMap<>();
        for (AnalyticsEvent e : events) {
            if (!"BOOKLET".equals(e.getEntityType()) || e.getEntityId() == null) {
                continue;
            }
            BookletAcc a = acc.computeIfAbsent(e.getEntityId(), BookletAcc::new);
            if (a.companyId == null) {
                a.companyId = e.getCompanyId();
            }
            if (e.getVid() != null) {
                a.uniques.add(e.getVid());
            }
            switch (e.getName()) {
                case "booklet_open" -> a.opens++;
                case "booklet_page_view" -> {
                    a.pageViews++;
                    Long page = e.longProp("page");
                    Long ms = e.longProp("activeMs");
                    if (ms != null) {
                        a.activeSum += ms;
                        a.activeCount++;
                    }
                    if (page != null) {
                        long[] p = a.pages.computeIfAbsent(page.intValue(), k -> new long[3]);
                        p[0]++;
                        if (ms != null) {
                            p[1] += ms;
                            p[2]++;
                        }
                    }
                }
                case "booklet_complete" -> {
                    Long pct = e.longProp("pct");
                    if (pct != null) {
                        a.completionSum += pct;
                        a.completionCount++;
                    }
                }
                default -> {
                }
            }
        }
        List<BookletRow> out = new ArrayList<>();
        for (BookletAcc a : acc.values()) {
            List<BookletPage> pages = new ArrayList<>();
            a.pages.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(en -> pages.add(new BookletPage(en.getKey(), en.getValue()[0], avg(en.getValue()[1], en.getValue()[2]))));
            out.add(new BookletRow(a.id, titles == null ? null : titles.get("BOOKLET:" + a.id), a.companyId, a.opens, a.uniques.size(),
                    a.pageViews, a.opens == 0 ? 0.0 : round2((double) a.pageViews / a.opens),
                    a.completionCount == 0 ? 0.0 : round2((double) a.completionSum / a.completionCount), avg(a.activeSum, a.activeCount), pages));
        }
        out.sort(Comparator.comparingLong(BookletRow::opens).reversed().thenComparing(BookletRow::entityId));
        return out;
    }

    public static List<ArticleRow> articles(List<AnalyticsEvent> events, Map<String, String> titles) {
        Map<String, long[]> acc = new LinkedHashMap<>();
        Map<String, Set<String>> uniques = new HashMap<>();
        for (AnalyticsEvent e : events) {
            if (e.getEntityType() == null || !ARTICLE_TYPES.contains(e.getEntityType()) || e.getEntityId() == null) {
                continue;
            }
            String key = e.entityKey();
            long[] a = acc.computeIfAbsent(key, k -> new long[6]);
            if (e.getVid() != null) {
                uniques.computeIfAbsent(key, k -> new HashSet<>()).add(e.getVid());
            }
            if ("article_read".equals(e.getName())) {
                a[0]++;
                Long pct = e.longProp("pct");
                if (pct != null) {
                    a[1] += pct;
                    a[2]++;
                }
                Long ms = e.longProp("activeMs");
                if (ms != null) {
                    a[3] += ms;
                    a[4]++;
                }
            } else if ("article_cta_click".equals(e.getName())) {
                a[5]++;
            }
        }
        List<ArticleRow> out = new ArrayList<>();
        acc.forEach((k, a) -> {
            String[] p = k.split(":", 2);
            out.add(new ArticleRow(p[0], p[1], titles == null ? null : titles.get(k), a[0], uniques.getOrDefault(k, Set.of()).size(),
                    a[2] == 0 ? 0.0 : round2((double) a[1] / a[2]), avg(a[3], a[4]), a[5]));
        });
        out.sort(Comparator.comparingLong(ArticleRow::reads).reversed().thenComparing(ArticleRow::entityId));
        return out;
    }

    public static List<CompanyRow> companies(List<AnalyticsEvent> events, List<Group> impressions, Map<String, String> titles) {
        Map<String, CompanyAcc> acc = new LinkedHashMap<>();
        for (Group g : impressions) {
            String c = g.get("companyId");
            if (c == null) {
                continue;
            }
            CompanyAcc a = acc.computeIfAbsent(c, CompanyAcc::new);
            a.impressions += g.count();
            if (g.get("entityType") != null && g.get("entityId") != null) {
                a.items.add(g.get("entityType") + ":" + g.get("entityId"));
            }
        }
        for (AnalyticsEvent e : events) {
            if (e.getCompanyId() == null || "item_impression".equals(e.getName())) {
                continue;
            }
            CompanyAcc a = acc.computeIfAbsent(e.getCompanyId(), CompanyAcc::new);
            if (e.getVid() != null) {
                a.uniques.add(e.getVid());
            }
            if (e.entityKey() != null && !"COMPANY".equals(e.getEntityType())) {
                a.items.add(e.entityKey());
            }
            switch (e.getName()) {
                case "company_view" -> a.companyViews++;
                case "item_click" -> a.clicks++;
                case "item_view" -> {
                    a.views++;
                    Long ms = e.longProp("activeMs");
                    if (ms != null) {
                        a.activeSum += ms;
                        a.activeCount++;
                    }
                }
                default -> {
                    if (SessionSummary.CONVERSIONS.contains(e.getName())) {
                        a.conversions++;
                    }
                }
            }
        }
        List<CompanyRow> out = new ArrayList<>();
        for (CompanyAcc a : acc.values()) {
            out.add(new CompanyRow(a.id, titles == null ? null : titles.get(a.id), a.companyViews, a.impressions, a.clicks,
                    ratio(a.clicks, a.impressions), a.views, a.uniques.size(), avg(a.activeSum, a.activeCount), a.conversions, a.items.size()));
        }
        out.sort(Comparator.comparingLong(CompanyRow::views).thenComparingLong(CompanyRow::impressions).reversed().thenComparing(CompanyRow::companyId));
        return out;
    }

    public static List<KeyCount> keyCounts(Map<String, Long> m, int limit) {
        List<KeyCount> out = new ArrayList<>();
        m.forEach((k, v) -> out.add(new KeyCount(k, v)));
        out.sort(Comparator.comparingLong(KeyCount::count).reversed().thenComparing(KeyCount::key, Comparator.nullsLast(Comparator.naturalOrder())));
        return limit(out, limit);
    }

    public static <T> List<T> limit(List<T> list, int limit) {
        return limit > 0 && list.size() > limit ? new ArrayList<>(list.subList(0, limit)) : list;
    }

    public static <T> List<T> page(List<T> list, int page, int size) {
        int from = Math.max(0, page) * Math.max(1, size);
        if (from >= list.size()) {
            return List.of();
        }
        return new ArrayList<>(list.subList(from, Math.min(list.size(), from + Math.max(1, size))));
    }

    static double p75(List<double[]> list, int idx) {
        List<Double> v = new ArrayList<>();
        for (double[] d : list) {
            if (!Double.isNaN(d[idx])) {
                v.add(d[idx]);
            }
        }
        if (v.isEmpty()) {
            return 0.0;
        }
        v.sort(Double::compare);
        int i = (int) Math.ceil(v.size() * 0.75) - 1;
        return round2(v.get(Math.max(0, Math.min(v.size() - 1, i))));
    }

    static double nn(Double d) {
        return d == null ? Double.NaN : d;
    }

    static String nz(String s) {
        return s == null ? "" : s;
    }

    public static ZoneId zone(AnalyticsQuery q) {
        return q.zone();
    }

    private static final class ItemAcc {
        final String type;
        final String id;
        String companyId;
        long impressions;
        long reach;
        long clicks;
        long views;
        long activeSum;
        long activeCount;
        long conversions;
        long positionSum;
        long positionCount;
        final Set<String> uniques = new HashSet<>();
        final Map<String, Long> sources = new LinkedHashMap<>();

        ItemAcc(String type, String id) {
            this.type = type;
            this.id = id;
        }
    }

    private static final class BookletAcc {
        final String id;
        String companyId;
        long opens;
        long pageViews;
        long activeSum;
        long activeCount;
        long completionSum;
        long completionCount;
        final Set<String> uniques = new HashSet<>();
        final Map<Integer, long[]> pages = new HashMap<>();

        BookletAcc(String id) {
            this.id = id;
        }
    }

    private static final class CompanyAcc {
        final String id;
        long companyViews;
        long impressions;
        long clicks;
        long views;
        long activeSum;
        long activeCount;
        long conversions;
        final Set<String> uniques = new HashSet<>();
        final Set<String> items = new HashSet<>();

        CompanyAcc(String id) {
            this.id = id;
        }
    }
}
