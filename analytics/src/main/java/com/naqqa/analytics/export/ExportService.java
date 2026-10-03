package com.naqqa.analytics.export;

import com.naqqa.analytics.query.AnalyticsDtos;
import com.naqqa.analytics.query.AnalyticsDtos.Kpis;
import com.naqqa.analytics.query.AnalyticsDtos.PartnerKpis;
import com.naqqa.analytics.query.AnalyticsDtos.Row;
import com.naqqa.analytics.query.AnalyticsDtos.Seg;
import com.naqqa.analytics.query.AnalyticsQuery;
import com.naqqa.analytics.query.AnalyticsQueryService;
import com.naqqa.analytics.web.AnalyticsException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ExportService {

    public static final Set<String> ADMIN_REPORTS = Set.of("overview", "acquisition", "behavior", "content", "companies", "search", "audience",
            "funnels", "cohorts", "quality");
    public static final Set<String> PARTNER_REPORTS = Set.of("partner-overview", "partner-items", "partner-booklets", "partner-searches",
            "partner-audience");
    private static final Map<String, String> PARTNER_ALIASES = Map.of("overview", "partner-overview", "content", "partner-items",
            "items", "partner-items", "booklets", "partner-booklets", "search", "partner-searches", "searches", "partner-searches",
            "audience", "partner-audience");

    private final AnalyticsQueryService queries;

    public ExportService(AnalyticsQueryService queries) {
        this.queries = queries;
    }

    public static String resolve(String report, boolean partner) {
        if (report == null) {
            throw AnalyticsException.badRequest("report is required");
        }
        String r = report.trim();
        if (partner) {
            r = PARTNER_ALIASES.getOrDefault(r, r);
            if (!PARTNER_REPORTS.contains(r)) {
                throw AnalyticsException.forbidden("Report not available");
            }
            return r;
        }
        if (!ADMIN_REPORTS.contains(r) && !PARTNER_REPORTS.contains(r)) {
            throw AnalyticsException.badRequest("Unknown report");
        }
        return r;
    }

    public static String format(String format) {
        String f = format == null ? "csv" : format.trim().toLowerCase();
        if (!"csv".equals(f) && !"xlsx".equals(f)) {
            throw AnalyticsException.badRequest("format must be csv or xlsx");
        }
        return f;
    }

    public byte[] export(String report, AnalyticsQuery q, String format) {
        List<Table> tables = tables(report, q);
        return "xlsx".equals(format) ? XlsxWriter.write(tables) : CsvWriter.write(tables);
    }

    public static String contentType(String format) {
        return "xlsx".equals(format) ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" : "text/csv; charset=UTF-8";
    }

    public static String fileName(String report, AnalyticsQuery q, String format) {
        return "analytics-" + report + "-" + q.from() + "_" + q.to() + "." + format;
    }

    public List<Table> tables(String report, AnalyticsQuery q) {
        return switch (report) {
            case "overview" -> overview(queries.overview(q));
            case "acquisition" -> acquisition(queries.acquisition(q));
            case "behavior" -> behavior(queries.behavior(q));
            case "content", "partner-items" -> List.of(items(queries.content(q).items()));
            case "companies" -> List.of(companies(queries.companies(q)));
            case "search" -> search(queries.search(q));
            case "audience", "partner-audience" -> audience(queries.audience(q, report.startsWith("partner")));
            case "funnels" -> funnel(queries.funnel(q, steps(q), q.param("scope")));
            case "cohorts" -> List.of(cohorts(queries.cohorts(q, weeks(q))));
            case "quality" -> quality(queries.quality(q));
            case "partner-overview" -> partnerOverview(queries.partnerOverview(q));
            case "partner-booklets" -> List.of(booklets(queries.partnerBooklets(q).booklets()));
            case "partner-searches" -> partnerSearches(queries.partnerSearches(q));
            default -> throw AnalyticsException.badRequest("Unknown report");
        };
    }

    public static List<String> steps(AnalyticsQuery q) {
        String s = q.param("steps");
        if (s == null || s.isBlank()) {
            return List.of("item_view", "promocode_click", "promocode_checkout_start", "promocode_purchase");
        }
        List<String> out = new ArrayList<>();
        for (String p : s.split(",")) {
            String v = p.trim();
            if (!v.isEmpty() && v.length() <= 80) {
                out.add(v);
            }
        }
        if (out.isEmpty() || out.size() > 8) {
            throw AnalyticsException.badRequest("1 to 8 steps required");
        }
        return out;
    }

    public static int weeks(AnalyticsQuery q) {
        try {
            return q.param("weeks") == null ? 8 : Integer.parseInt(q.param("weeks"));
        } catch (NumberFormatException e) {
            throw AnalyticsException.badRequest("Invalid weeks");
        }
    }

    private static List<Table> overview(AnalyticsDtos.Overview o) {
        Table kpis = Table.of("KPIs", "metric", "value", "compare");
        addKpis(kpis, o.kpis(), o.compare());
        Table series = Table.of("Series", "t", "visitors", "pageViews", "sessions");
        o.series().forEach(p -> series.row(p.t(), p.visitors(), p.pageViews(), p.sessions()));
        return List.of(kpis, series);
    }

    private static void addKpis(Table t, Kpis k, Kpis c) {
        t.row("visitors", k.visitors(), c == null ? null : c.visitors());
        t.row("newVisitors", k.newVisitors(), c == null ? null : c.newVisitors());
        t.row("returningVisitors", k.returningVisitors(), c == null ? null : c.returningVisitors());
        t.row("sessions", k.sessions(), c == null ? null : c.sessions());
        t.row("pageViews", k.pageViews(), c == null ? null : c.pageViews());
        t.row("pagesPerSession", k.pagesPerSession(), c == null ? null : c.pagesPerSession());
        t.row("avgActiveMs", k.avgActiveMs(), c == null ? null : c.avgActiveMs());
        t.row("bounceRate", k.bounceRate(), c == null ? null : c.bounceRate());
        t.row("impressions", k.impressions(), c == null ? null : c.impressions());
        t.row("clicks", k.clicks(), c == null ? null : c.clicks());
        t.row("ctr", k.ctr(), c == null ? null : c.ctr());
        t.row("conversions.promocode", k.conversions().promocode(), c == null ? null : c.conversions().promocode());
        t.row("conversions.share", k.conversions().share(), c == null ? null : c.conversions().share());
        t.row("conversions.addToList", k.conversions().addToList(), c == null ? null : c.conversions().addToList());
        t.row("conversions.contact", k.conversions().contact(), c == null ? null : c.conversions().contact());
        t.row("conversions.storeLink", k.conversions().storeLink(), c == null ? null : c.conversions().storeLink());
    }

    private static List<Table> partnerOverview(AnalyticsDtos.PartnerOverview o) {
        Table kpis = Table.of("KPIs", "metric", "value", "compare");
        PartnerKpis k = o.kpis();
        PartnerKpis c = o.compare();
        kpis.row("companyViews", k.companyViews(), c == null ? null : c.companyViews());
        kpis.row("visitors", k.visitors(), c == null ? null : c.visitors());
        kpis.row("reach", k.reach(), c == null ? null : c.reach());
        kpis.row("impressions", k.impressions(), c == null ? null : c.impressions());
        kpis.row("clicks", k.clicks(), c == null ? null : c.clicks());
        kpis.row("ctr", k.ctr(), c == null ? null : c.ctr());
        kpis.row("views", k.views(), c == null ? null : c.views());
        kpis.row("uniques", k.uniques(), c == null ? null : c.uniques());
        kpis.row("avgActiveMs", k.avgActiveMs(), c == null ? null : c.avgActiveMs());
        kpis.row("conversions", k.conversions().total(), c == null ? null : c.conversions().total());
        kpis.row("reviews", k.reviews(), c == null ? null : c.reviews());
        kpis.row("avgRating", k.avgRating(), c == null ? null : c.avgRating());
        Table series = Table.of("Series", "t", "impressions", "clicks", "views", "visitors");
        o.series().forEach(p -> series.row(p.t(), p.impressions(), p.clicks(), p.views(), p.visitors()));
        return List.of(kpis, series);
    }

    private static Table rows(String name, List<Row> rows) {
        Table t = Table.of(name, "key", "visitors", "sessions", "pageViews", "bounceRate", "avgActiveMs", "conversions");
        rows.forEach(r -> t.row(r.key(), r.visitors(), r.sessions(), r.pageViews(), r.bounceRate(), r.avgActiveMs(), r.conversions()));
        return t;
    }

    private static List<Table> acquisition(AnalyticsDtos.Acquisition a) {
        Table shares = Table.of("Shares", "entityType", "entityId", "title", "shares", "landings");
        a.shares().topShared().forEach(s -> shares.row(s.entityType(), s.entityId(), s.title(), s.shares(), s.landings()));
        return List.of(rows("Channels", a.channels()), rows("Sources", a.sources()), rows("Campaigns", a.campaigns()),
                rows("Landing pages", a.landingPages()), rows("Exit pages", a.exitPages()), shares);
    }

    private static List<Table> behavior(AnalyticsDtos.Behavior b) {
        Table pages = Table.of("Pages", "path", "pageType", "views", "visitors", "entrances", "exits", "avgActiveMs", "avgScroll");
        b.pages().forEach(p -> pages.row(p.path(), p.pageType(), p.views(), p.visitors(), p.entrances(), p.exits(), p.avgActiveMs(), p.avgScroll()));
        Table flows = Table.of("Flows", "from", "to", "count");
        b.flows().forEach(f -> flows.row(f.from(), f.to(), f.count()));
        Table vitals = Table.of("Web vitals", "path", "samples", "lcpP75", "inpP75", "clsP75");
        b.webVitals().forEach(v -> vitals.row(v.path(), v.samples(), v.lcpP75(), v.inpP75(), v.clsP75()));
        Table rage = Table.of("Rage clicks", "path", "target", "count");
        b.rageClicks().forEach(r -> rage.row(r.path(), r.target(), r.count()));
        return List.of(pages, flows, vitals, rage);
    }

    private static Table items(List<AnalyticsDtos.ContentItem> items) {
        Table t = Table.of("Items", "entityType", "entityId", "title", "companyId", "impressions", "reach", "clicks", "ctr", "views", "uniques",
                "avgActiveMs", "conversions", "avgPosition");
        items.forEach(i -> t.row(i.entityType(), i.entityId(), i.title(), i.companyId(), i.impressions(), i.reach(), i.clicks(), i.ctr(), i.views(),
                i.uniques(), i.avgActiveMs(), i.conversions(), i.avgPosition()));
        return t;
    }

    private static Table booklets(List<AnalyticsDtos.BookletRow> rows) {
        Table t = Table.of("Booklets", "entityId", "title", "opens", "uniques", "pageViews", "avgPagesPerOpen", "avgCompletionPct", "avgPageActiveMs");
        rows.forEach(b -> t.row(b.entityId(), b.title(), b.opens(), b.uniques(), b.pageViews(), b.avgPagesPerOpen(), b.avgCompletionPct(),
                b.avgPageActiveMs()));
        return t;
    }

    private static Table companies(AnalyticsDtos.Companies c) {
        Table t = Table.of("Companies", "companyId", "title", "companyViews", "impressions", "clicks", "ctr", "views", "uniques", "avgActiveMs",
                "conversions", "items");
        c.companies().forEach(r -> t.row(r.companyId(), r.title(), r.companyViews(), r.impressions(), r.clicks(), r.ctr(), r.views(), r.uniques(),
                r.avgActiveMs(), r.conversions(), r.items()));
        return t;
    }

    private static List<Table> search(AnalyticsDtos.Search s) {
        Table top = Table.of("Top searches", "q", "searches", "visitors", "avgResults", "clicks", "ctr");
        s.top().forEach(r -> top.row(r.q(), r.searches(), r.visitors(), r.avgResults(), r.clicks(), r.ctr()));
        Table zero = Table.of("Zero results", "q", "searches", "visitors");
        s.zeroResults().forEach(r -> zero.row(r.q(), r.searches(), r.visitors()));
        return List.of(top, zero);
    }

    private static List<Table> partnerSearches(AnalyticsDtos.PartnerSearches s) {
        Table top = Table.of("Searches", "q", "searches", "clicks");
        s.top().forEach(r -> top.row(r.q(), r.searches(), r.clicks()));
        Table zero = Table.of("Zero results", "q", "searches");
        s.zeroResults().forEach(r -> zero.row(r.q(), r.searches()));
        return List.of(top, zero);
    }

    private static Table segs(String name, List<Seg> segs) {
        Table t = Table.of(name, "key", "visitors", "share", "insufficient");
        segs.forEach(s -> t.row(s.key(), s.visitors(), s.share(), s.insufficient()));
        return t;
    }

    private static List<Table> audience(AnalyticsDtos.Audience a) {
        return Arrays.asList(segs("Devices", a.devices()), segs("OS", a.os()), segs("Browsers", a.browsers()), segs("Languages", a.languages()),
                segs("Cities", a.cities()), segs("Visitor types", a.visitorTypes()));
    }

    private static List<Table> funnel(AnalyticsDtos.Funnel f) {
        Table t = Table.of("Funnel", "step", "count", "rate", "dropoff");
        f.steps().forEach(s -> t.row(s.name(), s.count(), s.rate(), s.dropoff()));
        return List.of(t);
    }

    private static Table cohorts(AnalyticsDtos.Cohorts c) {
        int max = 0;
        for (AnalyticsDtos.Cohort x : c.cohorts()) {
            max = Math.max(max, x.retention().size());
        }
        List<String> headers = new ArrayList<>(List.of("week", "size"));
        for (int i = 0; i < max; i++) {
            headers.add("w" + i);
        }
        Table t = new Table("Cohorts", headers, new ArrayList<>());
        for (AnalyticsDtos.Cohort x : c.cohorts()) {
            List<Object> row = new ArrayList<>(List.of(x.week(), x.size()));
            row.addAll(x.retention());
            t.rows().add(row);
        }
        return t;
    }

    private static List<Table> quality(AnalyticsDtos.Quality q) {
        Table t = Table.of("Quality", "metric", "value");
        t.row("events", q.totals().events()).row("botEvents", q.totals().botEvents()).row("internalEvents", q.totals().internalEvents())
                .row("testEvents", q.totals().testEvents()).row("rejected", q.totals().rejected()).row("rateLimited", q.totals().rateLimited());
        Table reasons = Table.of("Rejected", "reason", "count");
        q.rejectedByReason().forEach(r -> reasons.row(r.key(), r.count()));
        return List.of(t, reasons);
    }
}
