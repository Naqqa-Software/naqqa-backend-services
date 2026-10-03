package com.naqqa.analytics.query;

import com.naqqa.analytics.collect.EntityLookup;
import com.naqqa.analytics.collect.QualityCounters;
import com.naqqa.analytics.collect.RealtimeCounters;
import com.naqqa.analytics.model.AnalyticsEvent;
import com.naqqa.analytics.model.RollupRow;
import com.naqqa.analytics.query.AnalyticsDtos.Acquisition;
import com.naqqa.analytics.query.AnalyticsDtos.Audience;
import com.naqqa.analytics.query.AnalyticsDtos.Behavior;
import com.naqqa.analytics.query.AnalyticsDtos.Benchmark;
import com.naqqa.analytics.query.AnalyticsDtos.Cohorts;
import com.naqqa.analytics.query.AnalyticsDtos.Companies;
import com.naqqa.analytics.query.AnalyticsDtos.CompanyRef;
import com.naqqa.analytics.query.AnalyticsDtos.CompanyRow;
import com.naqqa.analytics.query.AnalyticsDtos.Content;
import com.naqqa.analytics.query.AnalyticsDtos.ContentItem;
import com.naqqa.analytics.query.AnalyticsDtos.Funnel;
import com.naqqa.analytics.query.AnalyticsDtos.KeyCount;
import com.naqqa.analytics.query.AnalyticsDtos.Meta;
import com.naqqa.analytics.query.AnalyticsDtos.Overview;
import com.naqqa.analytics.query.AnalyticsDtos.PartnerBooklets;
import com.naqqa.analytics.query.AnalyticsDtos.PartnerItems;
import com.naqqa.analytics.query.AnalyticsDtos.PartnerOverview;
import com.naqqa.analytics.query.AnalyticsDtos.PartnerSearches;
import com.naqqa.analytics.query.AnalyticsDtos.Pipeline;
import com.naqqa.analytics.query.AnalyticsDtos.Quality;
import com.naqqa.analytics.query.AnalyticsDtos.QualityTotals;
import com.naqqa.analytics.query.AnalyticsDtos.Realtime;
import com.naqqa.analytics.query.AnalyticsDtos.RealtimeEvent;
import com.naqqa.analytics.query.AnalyticsDtos.RealtimePage;
import com.naqqa.analytics.query.AnalyticsDtos.Search;
import com.naqqa.analytics.query.AnalyticsDtos.SeriesPoint;
import com.naqqa.analytics.query.EventSource.Group;
import com.naqqa.analytics.rollup.Rollups;
import com.naqqa.analytics.spi.AnalyticsScopeResolver;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public class AnalyticsQueryService {

    public static final Set<String> NO_IMPRESSIONS = Set.of("item_impression");
    private static final Set<String> SEARCH_EVENTS = Set.of("search", "search_result_click", "filter_apply", "sort_change", "search_refine");

    public interface RollupLoader {
        List<RollupRow> load(LocalDate from, LocalDate to, String metric, Map<String, String> dims);
    }

    public interface QualitySource {
        Map<String, Long> rejected(LocalDate from, LocalDate to);

        long queueSize();

        long backlog();
    }

    private final EventSource source;
    private final EntityLookup entities;
    private final AnalyticsScopeResolver scopes;
    private final RealtimeCounters realtimeCounters;
    private final QualityCounters quality;
    private final QueryCache cache;
    private final RollupLoader rollups;
    private final QualitySource qualitySource;
    private final Clock clock;
    private final int kAnonymity;
    private final int benchmarkMinPartners;
    private final int topLimit;
    private final int realtimeWindowMinutes;
    private final boolean useRollups;

    public AnalyticsQueryService(EventSource source, EntityLookup entities, AnalyticsScopeResolver scopes, RealtimeCounters realtimeCounters,
                                 QualityCounters quality, QueryCache cache, RollupLoader rollups, QualitySource qualitySource, Clock clock,
                                 int kAnonymity, int benchmarkMinPartners, int topLimit, int realtimeWindowMinutes, boolean useRollups) {
        this.source = source;
        this.entities = entities;
        this.scopes = scopes == null ? AnalyticsScopeResolver.NONE : scopes;
        this.realtimeCounters = realtimeCounters;
        this.quality = quality;
        this.cache = cache;
        this.rollups = rollups;
        this.qualitySource = qualitySource;
        this.clock = clock;
        this.kAnonymity = kAnonymity;
        this.benchmarkMinPartners = benchmarkMinPartners;
        this.topLimit = topLimit;
        this.realtimeWindowMinutes = realtimeWindowMinutes;
        this.useRollups = useRollups;
    }

    public int kAnonymity() {
        return kAnonymity;
    }

    public Meta meta(AnalyticsQuery q, String src) {
        return new Meta(q.from().toString(), q.to().toString(), q.compareFrom() == null ? null : q.compareFrom().toString(),
                q.compareTo() == null ? null : q.compareTo().toString(), q.granularity(), q.zone().getId(),
                q.companyIds() == null ? null : new ArrayList<>(q.companyIds()), clock.millis(), src, kAnonymity);
    }

    private <T> T cached(String report, AnalyticsQuery q, java.util.function.Supplier<T> loader) {
        return cache == null ? loader.get() : cache.get(report + "|" + q.cacheKey(), loader);
    }

    private List<Group> impressions(AnalyticsQuery q, String... keys) {
        return source.group(q, "item_impression", Arrays.asList(keys));
    }

    public Overview overview(AnalyticsQuery q) {
        return cached("overview", q, () -> {
            List<AnalyticsEvent> events = source.events(q, null, NO_IMPRESSIONS);
            long impressions = Reports.sumGroups(impressions(q, "companyId"));
            AnalyticsDtos.Kpis kpis = Reports.kpis(events, impressions);
            String src = "raw";
            List<SeriesPoint> series = null;
            if (rollupEligible(q)) {
                series = rollupSeries(q);
                if (series != null) {
                    src = "rollup";
                }
            }
            if (series == null) {
                series = Reports.series(events, q);
            }
            AnalyticsDtos.Kpis compare = null;
            List<SeriesPoint> compareSeries = List.of();
            AnalyticsQuery cq = q.compareQuery();
            if (cq != null) {
                List<AnalyticsEvent> ce = source.events(cq, null, NO_IMPRESSIONS);
                compare = Reports.kpis(ce, Reports.sumGroups(impressions(cq, "companyId")));
                compareSeries = Reports.series(ce, cq);
            }
            List<SessionSummary> sessions = SessionSummary.of(events);
            int k = q.scoped() ? kAnonymity : 0;
            int top = Math.min(topLimit, 10);
            List<ContentItem> topEntities = withTitles(Reports.limit(Reports.sortItems(
                    Reports.items(events, impressions(q, "entityType", "entityId", "companyId"), null, null), "views", "desc"), top));
            return new Overview(meta(q, src), kpis, compare, series, compareSeries,
                    Insights.segments(events, AnalyticsEvent::getCountry, k, top),
                    Insights.segments(events, AnalyticsEvent::getRegion, k, top),
                    Insights.segments(events, AnalyticsEvent::getCity, k, top),
                    Insights.segments(events, AnalyticsEvent::getDevice, k, 0),
                    Insights.segments(events, AnalyticsEvent::getBrowser, k, top),
                    Insights.segments(events, AnalyticsEvent::getOs, k, top),
                    Insights.segments(events, e -> e.getSource() == null ? "(direct)" : e.getSource(), k, top),
                    Insights.segments(events, e -> e.getReferrer() == null ? "(none)" : e.getReferrer(), k, top),
                    Insights.segments(events, AnalyticsEvent::getLang, k, 0),
                    Reports.pages(events, sessions, top),
                    Reports.rows(sessions, SessionSummary::landing, top),
                    Insights.segments(events, e -> e.getNewVisitor() == null ? "unknown" : e.getNewVisitor() ? "new" : "returning", k, 0),
                    topEntities);
        });
    }

    boolean rollupEligible(AnalyticsQuery q) {
        return useRollups && rollups != null && q.filters().isEmpty() && !q.scoped() && !q.includeBots() && !q.includeInternal()
                && AnalyticsQuery.DAY.equals(q.granularity()) && q.to().isBefore(LocalDate.now(clock.withZone(q.zone())));
    }

    List<SeriesPoint> rollupSeries(AnalyticsQuery q) {
        try {
            List<RollupRow> site = rollups.load(q.from(), q.to(), Rollups.SITE, null);
            List<RollupRow> sessions = rollups.load(q.from(), q.to(), Rollups.SITE_SESSIONS, null);
            Map<String, RollupRow> siteByDay = new HashMap<>();
            site.forEach(r -> siteByDay.put(r.getDay(), r));
            Map<String, RollupRow> sessionsByDay = new HashMap<>();
            sessions.forEach(r -> sessionsByDay.put(r.getDay(), r));
            List<String> days = Buckets.range(q.from(), q.to(), AnalyticsQuery.DAY);
            for (String d : days) {
                if (!siteByDay.containsKey(d)) {
                    return null;
                }
            }
            List<SeriesPoint> out = new ArrayList<>();
            for (String d : days) {
                RollupRow s = siteByDay.get(d);
                RollupRow ss = sessionsByDay.get(d);
                out.add(new SeriesPoint(d, s.getUniques(), s.getCount(), ss == null ? 0 : ss.getCount()));
            }
            return out;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public Realtime realtime(Set<String> companyIds) {
        long now = clock.millis();
        long since = now - realtimeWindowMinutes * 60_000L;
        List<AnalyticsEvent> recent = source.recent(since, companyIds, 50_000);
        Insights.RealtimeResult r = Insights.realtime(recent, now, realtimeWindowMinutes, companyIds == null ? 0 : kAnonymity);
        long today = 0;
        if (realtimeCounters != null) {
            String day = LocalDate.now(clock.withZone(java.time.ZoneId.of(zoneId()))).toString();
            try {
                if (companyIds == null) {
                    today = realtimeCounters.visitors(day);
                } else {
                    for (String c : companyIds) {
                        today += realtimeCounters.companyVisitors(day, c);
                    }
                }
            } catch (RuntimeException ignored) {
                today = 0;
            }
        }
        if (companyIds != null && today > 0 && today < kAnonymity) {
            today = 0;
        }
        return new Realtime(r.activeVisitors(), today, realtimeWindowMinutes, r.views(), r.pages(), r.topPaths(), r.byCountry(), r.perMinute(),
                r.events());
    }

    public static final Set<String> DIMENSIONS = Set.of("entityType", "entityId", "path", "source", "campaign", "city", "region", "country",
            "lang", "device", "browser", "os", "referrer", "channel", "pageType", "landing", "sourceBlock", "categoryId", "companyId");

    public AnalyticsDtos.Timeseries timeseries(AnalyticsQuery q) {
        String name = q.param("name");
        return cached("timeseries", q, () -> {
            List<AnalyticsEvent> events = "item_impression".equals(name) ? source.events(q, null, null) : source.events(q, null, NO_IMPRESSIONS);
            return new AnalyticsDtos.Timeseries(meta(q, "raw"), name, Insights.timeseries(events, q, name));
        });
    }

    public AnalyticsDtos.TopEntities topEntities(AnalyticsQuery q) {
        int limit = limitParam(q, 20, 200);
        return cached("top-entities|" + limit, q, () -> {
            List<AnalyticsEvent> events = source.events(q, null, NO_IMPRESSIONS);
            List<ContentItem> items = Reports.items(events, impressions(q, "entityType", "entityId", "companyId"), q.filter("entityType"), null);
            String sort = q.sort() == null ? "views" : q.sort();
            return new AnalyticsDtos.TopEntities(meta(q, "raw"), withTitles(Reports.limit(Reports.sortItems(items, sort, q.dir()), limit)));
        });
    }

    public AnalyticsDtos.EntityEvents entityEvents(AnalyticsQuery q) {
        int limit = limitParam(q, 50, 200);
        return cached("events|" + limit, q, () -> {
            List<AnalyticsEvent> events = source.events(q, null, NO_IMPRESSIONS);
            long impressions = 0;
            long impressionVisitors = 0;
            for (Group g : impressions(q, "entityType")) {
                impressions += g.count();
                impressionVisitors += g.uniques();
            }
            return new AnalyticsDtos.EntityEvents(meta(q, "raw"), q.filter("entityType"), q.filter("entityId"),
                    Reports.limit(Insights.eventCounts(events, impressions, impressionVisitors), limit));
        });
    }

    public AnalyticsDtos.Dimensions dimensions(AnalyticsQuery q, String name) {
        if (name == null || !DIMENSIONS.contains(name) || q.scoped() && "companyId".equals(name)) {
            throw new IllegalArgumentException("Unknown dimension");
        }
        int limit = limitParam(q, 50, 500);
        String prefix = q.param("q");
        return cached("dimensions|" + name + "|" + prefix + "|" + limit, q, () -> {
            List<AnalyticsDtos.DimensionValue> values = new ArrayList<>();
            for (EventSource.DimensionCount d : source.dimension(q.withoutFilter(name), name, prefix, limit)) {
                values.add(new AnalyticsDtos.DimensionValue(d.value(), d.count()));
            }
            return new AnalyticsDtos.Dimensions(name, values);
        });
    }

    static int limitParam(AnalyticsQuery q, int fallback, int max) {
        try {
            String v = q.param("limit");
            return v == null ? fallback : Math.max(1, Math.min(max, Integer.parseInt(v)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private String zone;

    public void setZone(String zone) {
        this.zone = zone;
    }

    private String zoneId() {
        return zone == null ? "UTC" : zone;
    }

    public Acquisition acquisition(AnalyticsQuery q) {
        return cached("acquisition", q, () -> {
            List<AnalyticsEvent> events = source.events(q, null, NO_IMPRESSIONS);
            List<SessionSummary> sessions = SessionSummary.of(events);
            Map<String, String> titles = titles(entityKeys(events, Set.of("share_click", "share_landing")));
            return new Acquisition(meta(q, "raw"),
                    Reports.rows(sessions, SessionSummary::channel, 0),
                    Reports.rows(sessions, s -> s.source() == null ? "(direct)" : s.source(), topLimit),
                    Reports.rows(sessions.stream().filter(s -> Reports.campaignKey(s) != null).toList(), Reports::campaignKey, topLimit),
                    Reports.rows(sessions, SessionSummary::landing, topLimit),
                    Reports.rows(sessions, SessionSummary::exit, topLimit),
                    Reports.shares(events, sessions, titles, topLimit));
        });
    }

    public Behavior behavior(AnalyticsQuery q) {
        return cached("behavior", q, () -> {
            List<AnalyticsEvent> events = source.events(q, null, NO_IMPRESSIONS);
            List<SessionSummary> sessions = SessionSummary.of(events);
            boolean byPath = "path".equals(q.param("flowBy"));
            return new Behavior(meta(q, "raw"), Reports.pages(events, sessions, topLimit * 2), Reports.flows(sessions, byPath, 100),
                    Reports.scroll(events), Reports.time(sessions), Reports.clicks(events, "rage_click", topLimit),
                    Reports.clicks(events, "dead_click", topLimit), Reports.vitals(events, topLimit), Reports.errors(events, topLimit));
        });
    }

    public Content content(AnalyticsQuery q) {
        return cached("content", q, () -> {
            String type = q.filter("entityType");
            List<AnalyticsEvent> events = source.events(q, null, NO_IMPRESSIONS);
            List<Group> impressions = impressions(q, "entityType", "entityId", "companyId");
            List<ContentItem> all = Reports.sortItems(Reports.items(events, impressions, type, null), q.sort(), q.dir());
            List<ContentItem> page = withTitles(Reports.page(all, q.page(), q.size()));
            Map<String, String> titles = titles(entityKeys(events, Set.of("booklet_open", "booklet_page_view", "article_read")));
            return new Content(meta(q, "raw"), all.size(), page,
                    type == null || "BOOKLET".equals(type) ? Reports.limit(Reports.booklets(events, titles), topLimit) : List.of(),
                    type == null || Reports.ARTICLE_TYPES.contains(type) ? Reports.limit(Reports.articles(events, titles), topLimit) : List.of());
        });
    }

    public Companies companies(AnalyticsQuery q) {
        return cached("companies", q, () -> {
            List<AnalyticsEvent> events = source.events(q, null, NO_IMPRESSIONS);
            List<Group> impressions = impressions(q, "entityType", "entityId", "companyId");
            List<CompanyRow> rows = Reports.companies(events, impressions, null);
            Set<String> ids = new LinkedHashSet<>();
            rows.forEach(r -> ids.add(r.companyId()));
            Map<String, String> titles = companyTitles(ids);
            List<CompanyRow> out = new ArrayList<>();
            for (CompanyRow r : rows) {
                out.add(new CompanyRow(r.companyId(), titles.get(r.companyId()), r.companyViews(), r.impressions(), r.clicks(), r.ctr(), r.views(),
                        r.uniques(), r.avgActiveMs(), r.conversions(), r.items()));
            }
            return new Companies(meta(q, "raw"), out);
        });
    }

    public Search search(AnalyticsQuery q) {
        return cached("search", q, () -> {
            List<AnalyticsEvent> events = source.events(q.withoutFilter("companyId"), SEARCH_EVENTS, null);
            Insights.SearchResult r = Insights.search(events, topLimit);
            return new Search(meta(q, "raw"), r.totals(), r.top(), r.zeroResults(), r.filters(), r.sorts());
        });
    }

    public Funnel funnel(AnalyticsQuery q, List<String> steps, String scope) {
        String sc = Insights.SCOPE_VISITOR.equals(scope) ? Insights.SCOPE_VISITOR : Insights.SCOPE_SESSION;
        return cached("funnel|" + steps + "|" + sc, q, () -> {
            Set<String> names = new HashSet<>();
            for (String s : steps) {
                int c = s.indexOf(':');
                names.add(c < 0 ? s : s.substring(0, c));
            }
            List<AnalyticsEvent> events = source.events(q, names, null);
            List<AnalyticsDtos.FunnelStep> out = Insights.funnel(events, steps, sc);
            double conversion = out.isEmpty() || out.get(0).count() == 0 ? 0.0 : Reports.ratio(out.get(out.size() - 1).count(), out.get(0).count());
            return new Funnel(meta(q, "raw"), sc, out, conversion);
        });
    }

    public Cohorts cohorts(AnalyticsQuery q, int weeks) {
        return cached("cohorts|" + weeks, q, () -> {
            List<AnalyticsEvent> events = source.events(q, null, NO_IMPRESSIONS);
            return new Cohorts(meta(q, "raw"), Insights.cohorts(events, q.from(), q.to(), Math.max(1, Math.min(26, weeks)), q.zone()));
        });
    }

    public Audience audience(AnalyticsQuery q, boolean partner) {
        return cached("audience|" + partner, q, () -> {
            List<AnalyticsEvent> events = source.events(q, null, NO_IMPRESSIONS);
            int k = partner ? kAnonymity : 0;
            return new Audience(meta(q, "raw"),
                    Insights.segments(events, AnalyticsEvent::getDevice, k, 0),
                    Insights.segments(events, AnalyticsEvent::getOs, k, 0),
                    Insights.segments(events, AnalyticsEvent::getBrowser, k, 0),
                    Insights.segments(events, AnalyticsEvent::getLang, k, 0),
                    Insights.segments(events, AnalyticsEvent::getCity, k, topLimit),
                    Insights.segments(events, AnalyticsEvent::getRegion, k, topLimit),
                    Insights.segments(events, AnalyticsEvent::getCountry, k, topLimit),
                    Insights.segments(events, e -> e.getNewVisitor() == null ? "unknown" : e.getNewVisitor() ? "new" : "returning", k, 0),
                    Insights.heatmap(events, q.zone(), k));
        });
    }

    public Quality quality(AnalyticsQuery q) {
        AnalyticsQuery all = new AnalyticsQuery(q.from(), q.to(), null, null, q.granularity(), Map.of(), null, true, true, 0, 50, null, null,
                q.zone(), Map.of());
        long events = 0;
        long bots = 0;
        long internal = 0;
        long test = 0;
        Map<String, Long> botReasons = new TreeMap<>();
        for (Group g : source.group(all, "item_impression", List.of())) {
            events += g.count();
        }
        for (AnalyticsEvent e : source.events(all, null, NO_IMPRESSIONS)) {
            events++;
            if (e.isBot()) {
                bots++;
                botReasons.merge(e.getBotReason() == null ? "unknown" : e.getBotReason(), 1L, Long::sum);
            } else if (e.isInternal()) {
                internal++;
            } else if (e.isTest()) {
                test++;
            }
        }
        Map<String, Long> rejected = qualitySource == null ? Map.of() : qualitySource.rejected(q.from(), q.to());
        long rejectedTotal = 0;
        long rateLimited = 0;
        List<KeyCount> byReason = new ArrayList<>();
        for (Map.Entry<String, Long> en : rejected.entrySet()) {
            if ("rate_limited".equals(en.getKey())) {
                rateLimited += en.getValue();
            } else if (!"dropped_props".equals(en.getKey())) {
                rejectedTotal += en.getValue();
            }
            byReason.add(new KeyCount(en.getKey(), en.getValue()));
        }
        byReason.sort(Comparator.comparingLong(KeyCount::count).reversed());
        long dropped = rejected.getOrDefault(QualityCounters.DROPPED, 0L);
        Pipeline pipeline = new Pipeline(qualitySource == null ? 0 : qualitySource.queueSize(), qualitySource == null ? 0 : qualitySource.backlog(),
                quality == null ? 0 : quality.avgDelayMs(), quality == null ? 0 : quality.p95DelayMs(), quality == null ? null : quality.lastFlushAt(),
                quality == null ? null : quality.lastRollupAt(), dropped);
        List<KeyCount> botRows = new ArrayList<>();
        botReasons.forEach((k, v) -> botRows.add(new KeyCount(k, v)));
        return new Quality(meta(q, "raw"), new QualityTotals(events, bots, internal, test, rejectedTotal, rateLimited), byReason, botRows, pipeline);
    }

    public PartnerOverview partnerOverview(AnalyticsQuery q) {
        return cached("p-overview", q, () -> {
            List<AnalyticsEvent> events = source.events(q, null, NO_IMPRESSIONS);
            AnalyticsDtos.PartnerKpis kpis = Insights.partnerKpis(events, impressions(q, "companyId"));
            AnalyticsDtos.PartnerKpis compare = null;
            AnalyticsQuery cq = q.compareQuery();
            if (cq != null) {
                compare = Insights.partnerKpis(source.events(cq, null, NO_IMPRESSIONS), impressions(cq, "companyId"));
            }
            return new PartnerOverview(meta(q, "raw"), kpis, compare, Insights.partnerSeries(events, impressions(q, "day"), q));
        });
    }

    public PartnerItems partnerItems(AnalyticsQuery q) {
        return cached("p-items", q, () -> {
            Content c = content(q);
            return new PartnerItems(c.meta(), c.total(), c.items());
        });
    }

    public PartnerBooklets partnerBooklets(AnalyticsQuery q) {
        return cached("p-booklets", q, () -> {
            AnalyticsQuery bq = q.withFilters(with(q.filters(), "entityType", "BOOKLET"));
            List<AnalyticsEvent> events = source.events(bq, null, NO_IMPRESSIONS);
            Map<String, String> titles = titles(entityKeys(events, null));
            return new PartnerBooklets(meta(q, "raw"), Reports.booklets(events, titles));
        });
    }

    public PartnerSearches partnerSearches(AnalyticsQuery q) {
        return cached("p-searches", q, () -> {
            Set<String> own = q.companyIds() == null ? Set.of() : q.companyIds();
            Set<String> categories = new HashSet<>();
            for (AnalyticsEvent e : source.events(q, null, NO_IMPRESSIONS)) {
                if (e.getCategoryId() != null) {
                    categories.add(e.getCategoryId());
                }
            }
            AnalyticsQuery global = q.withCompanyIds(null).withFilters(Map.of());
            List<AnalyticsEvent> searchEvents = source.events(global, Set.of("search", "search_result_click"), null);
            return Insights.partnerSearches(meta(q, "raw"), searchEvents, own, categories, kAnonymity, topLimit);
        });
    }

    public Benchmark partnerBenchmark(AnalyticsQuery q) {
        return cached("p-benchmark", q, () -> {
            Set<String> own = q.companyIds() == null ? Set.of() : q.companyIds();
            Set<String> categories = new LinkedHashSet<>();
            for (AnalyticsEvent e : source.events(q, null, NO_IMPRESSIONS)) {
                if (e.getCategoryId() != null) {
                    categories.add(e.getCategoryId());
                }
            }
            AnalyticsQuery global = q.withCompanyIds(null).withFilters(Map.of());
            List<AnalyticsEvent> events = categories.isEmpty() ? List.of()
                    : source.events(global, Set.of("item_click", "item_view", "promocode_click", "share_click", "add_to_list",
                    "store_contact_click", "store_link_click"), null);
            List<Group> impressions = categories.isEmpty() ? List.of() : source.group(global, "item_impression", List.of("categoryId", "companyId"));
            return new Benchmark(meta(q, "raw"), Insights.benchmark(events, impressions, own, categories, benchmarkMinPartners));
        });
    }

    public List<CompanyRef> companyRefs(Set<String> ids) {
        Map<String, String> titles = companyTitles(ids);
        List<CompanyRef> out = new ArrayList<>();
        for (String id : ids) {
            out.add(new CompanyRef(id, titles.get(id)));
        }
        return out;
    }

    private Map<String, String> companyTitles(Set<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        try {
            Map<String, String> m = scopes.companyTitles(ids);
            return m == null ? Map.of() : m;
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    private List<ContentItem> withTitles(List<ContentItem> items) {
        Map<String, Set<String>> byType = new LinkedHashMap<>();
        for (ContentItem i : items) {
            byType.computeIfAbsent(i.entityType(), k -> new LinkedHashSet<>()).add(i.entityId());
        }
        Map<String, String> titles = titles(byType);
        Map<String, String> companies = new HashMap<>();
        for (ContentItem i : items) {
            if ("COMPANY".equals(i.entityType())) {
                companies.put(i.entityId(), null);
            }
        }
        if (!companies.isEmpty()) {
            titles.putAll(prefix("COMPANY:", companyTitles(companies.keySet())));
        }
        List<ContentItem> out = new ArrayList<>();
        for (ContentItem i : items) {
            out.add(new ContentItem(i.entityType(), i.entityId(), titles.get(i.entityType() + ":" + i.entityId()), i.companyId(), i.impressions(),
                    i.reach(), i.clicks(), i.ctr(), i.views(), i.uniques(), i.avgActiveMs(), i.conversions(), i.avgPosition(), i.sources()));
        }
        return out;
    }

    private static Map<String, String> prefix(String p, Map<String, String> m) {
        Map<String, String> out = new HashMap<>();
        m.forEach((k, v) -> out.put(p + k, v));
        return out;
    }

    private Map<String, String> titles(Map<String, Set<String>> byType) {
        Map<String, String> out = new HashMap<>();
        if (entities == null) {
            return out;
        }
        byType.forEach((type, ids) -> {
            if (type == null || "COMPANY".equals(type)) {
                return;
            }
            entities.findAll(type, ids).forEach((id, info) -> {
                if (info.title() != null) {
                    out.put(type + ":" + id, info.title());
                }
            });
        });
        return out;
    }

    private static Map<String, Set<String>> entityKeys(List<AnalyticsEvent> events, Set<String> names) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (AnalyticsEvent e : events) {
            if (e.getEntityType() != null && e.getEntityId() != null && (names == null || names.contains(e.getName()))) {
                Set<String> ids = out.computeIfAbsent(e.getEntityType(), k -> new LinkedHashSet<>());
                if (ids.size() < 500) {
                    ids.add(e.getEntityId());
                }
            }
        }
        return out;
    }

    private static Map<String, String> with(Map<String, String> m, String k, String v) {
        Map<String, String> out = new LinkedHashMap<>(m);
        out.put(k, v);
        return out;
    }

}
