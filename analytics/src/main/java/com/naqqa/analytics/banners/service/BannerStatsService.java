package com.naqqa.analytics.banners.service;

import com.naqqa.analytics.banners.engine.BannerPacingCalculator;
import com.naqqa.analytics.banners.model.BannerCampaign;
import com.naqqa.analytics.banners.service.BannerStatsCalculator.Row;
import com.naqqa.analytics.banners.service.BannerStatsCalculator.Stats;
import com.naqqa.analytics.banners.store.BannerRepository;
import com.naqqa.analytics.model.AnalyticsEvent;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class BannerStatsService {

    public record CampaignRow(String id, String name, String companyId, String status, String priority, long served, long impressions,
                              long viewable, long clicks, double ctr, double viewabilityRate, BannerPacingCalculator.Pacing pacing) {
    }

    public record CampaignReport(BannerCampaign campaign, BannerPacingCalculator.Pacing pacing, Stats stats, String from, String to) {
    }

    public record OverviewReport(Stats stats, List<CampaignRow> campaigns, String from, String to) {
    }

    private final MongoTemplate mongo;
    private final BannerRepository repository;
    private final BannerStatsCalculator calculator;
    private final BannerPacingCalculator pacing;
    private final Clock clock;
    private final ZoneId zone;
    private final String eventCollection;
    private final int maxEvents;
    private final Duration postClickWindow;
    private final Collection<String> conversionEvents;

    public BannerStatsService(MongoTemplate mongo, BannerRepository repository, BannerStatsCalculator calculator, BannerPacingCalculator pacing,
                              Clock clock, ZoneId zone, String eventCollection, int maxEvents, Duration postClickWindow,
                              Collection<String> conversionEvents) {
        this.mongo = mongo;
        this.repository = repository;
        this.calculator = calculator;
        this.pacing = pacing;
        this.clock = clock;
        this.zone = zone;
        this.eventCollection = eventCollection;
        this.maxEvents = maxEvents;
        this.postClickWindow = postClickWindow;
        this.conversionEvents = conversionEvents;
    }

    public CampaignReport campaign(BannerCampaign campaign, LocalDate from, LocalDate to) {
        LocalDate[] range = range(from, to, campaign);
        List<AnalyticsEvent> events = bannerEvents(List.of(campaign.getId()), range[0], range[1]);
        Stats stats = calculator.compute(events, sessionEvents(events, range[0], range[1]));
        return new CampaignReport(campaign, pacing.pacing(campaign, clock.instant()), stats, range[0].toString(), range[1].toString());
    }

    public OverviewReport overview(LocalDate from, LocalDate to, Collection<String> companyIds) {
        LocalDate[] range = range(from, to, null);
        List<BannerCampaign> campaigns = repository.list(new BannerRepository.CampaignFilter(null, null, null, null, companyIds, null), 0, 200).content();
        List<String> ids = campaigns.stream().map(BannerCampaign::getId).toList();
        List<AnalyticsEvent> events = ids.isEmpty() ? List.of() : bannerEvents(ids, range[0], range[1]);
        Stats stats = calculator.compute(events, sessionEvents(events, range[0], range[1]));
        Map<String, Row> byCampaign = new HashMap<>();
        for (Row r : stats.breakdowns().getOrDefault("campaignId", List.of())) {
            byCampaign.put(r.key(), r);
        }
        Instant now = clock.instant();
        List<CampaignRow> rows = new ArrayList<>();
        for (BannerCampaign c : campaigns) {
            Row r = byCampaign.get(c.getId());
            rows.add(new CampaignRow(c.getId(), c.getName(), c.getCompanyId(), String.valueOf(c.getStatus()), String.valueOf(c.getPriority()),
                    c.getServedImpressions(), r == null ? 0 : r.impressions(), r == null ? 0 : r.viewable(), r == null ? 0 : r.clicks(),
                    r == null ? 0 : r.ctr(), r == null ? 0 : r.viewabilityRate(), pacing.pacing(c, now)));
        }
        return new OverviewReport(stats, rows, range[0].toString(), range[1].toString());
    }

    private static final Map<String, String> VIEW_KEYS = Map.ofEntries(
            Map.entry("slot", "bySlot"), Map.entry("creativeId", "byCreative"), Map.entry("campaignId", "byCampaign"),
            Map.entry("pageType", "byPageType"), Map.entry("categoryId", "byCategory"), Map.entry("device", "byDevice"),
            Map.entry("lang", "byLang"), Map.entry("city", "byCity"), Map.entry("channel", "byChannel"),
            Map.entry("hour", "byHour"), Map.entry("weekday", "byWeekday"), Map.entry("path", "byPath"));

    public static Map<String, Object> view(Stats stats, String from, String to) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", true);
        out.put("from", from);
        out.put("to", to);
        Map<String, Object> totals = new LinkedHashMap<>();
        BannerStatsCalculator.Totals t = stats.totals();
        totals.put("impressions", t.impressions());
        totals.put("viewable", t.viewable());
        totals.put("viewabilityRate", t.viewabilityRate());
        totals.put("clicks", t.clicks());
        totals.put("ctr", t.ctr());
        totals.put("uniques", t.uniques());
        totals.put("frequency", t.frequency());
        totals.put("avgVisibleMs", t.avgVisibleMs());
        totals.put("suspectClicks", t.suspectClicks());
        totals.put("conversions", stats.postClick().conversions());
        totals.put("conversionRate", stats.postClick().conversionRate());
        out.put("totals", totals);
        List<Map<String, Object>> series = new ArrayList<>();
        for (BannerStatsCalculator.Point p : stats.series()) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("t", p.day());
            point.put("impressions", p.impressions());
            point.put("viewable", p.viewable());
            point.put("clicks", p.clicks());
            series.add(point);
        }
        out.put("series", series);
        stats.breakdowns().forEach((dim, rows) -> out.put(VIEW_KEYS.getOrDefault(dim, "by" + Character.toUpperCase(dim.charAt(0)) + dim.substring(1)), rows));
        out.put("postClick", stats.postClick());
        return out;
    }

    public static Map<String, Object> view(CampaignReport report) {
        Map<String, Object> out = view(report.stats(), report.from(), report.to());
        out.put("campaign", report.campaign());
        out.put("pacing", report.pacing());
        return out;
    }

    public static Map<String, Object> view(OverviewReport report) {
        Map<String, Object> out = view(report.stats(), report.from(), report.to());
        out.put("campaigns", report.campaigns());
        return out;
    }

    public static String csv(CampaignReport report) {
        StringBuilder sb = new StringBuilder("﻿");
        Stats s = report.stats();
        line(sb, "campaign", report.campaign().getName());
        line(sb, "from", report.from());
        line(sb, "to", report.to());
        line(sb, "served", report.campaign().getServedImpressions());
        line(sb, "impressions", s.totals().impressions());
        line(sb, "viewable", s.totals().viewable());
        line(sb, "viewability_rate", s.totals().viewabilityRate());
        line(sb, "clicks", s.totals().clicks());
        line(sb, "ctr", s.totals().ctr());
        line(sb, "uniques", s.totals().uniques());
        line(sb, "frequency", s.totals().frequency());
        line(sb, "avg_visible_ms", s.totals().avgVisibleMs());
        line(sb, "suspect_clicks", s.totals().suspectClicks());
        line(sb, "post_click_sessions", s.postClick().sessions());
        line(sb, "post_click_page_views", s.postClick().pageViews());
        line(sb, "post_click_conversions", s.postClick().conversions());
        line(sb, "post_click_conversion_rate", s.postClick().conversionRate());
        if (report.pacing() != null) {
            line(sb, "pacing_state", report.pacing().state());
            line(sb, "pacing_expected", report.pacing().expected());
            line(sb, "pacing_projected_end", report.pacing().projectedEnd());
        }
        sb.append("\r\n");
        line(sb, "day", "impressions", "viewable", "clicks");
        for (BannerStatsCalculator.Point p : s.series()) {
            line(sb, p.day(), p.impressions(), p.viewable(), p.clicks());
        }
        for (Map.Entry<String, List<Row>> e : s.breakdowns().entrySet()) {
            if ("campaignId".equals(e.getKey())) {
                continue;
            }
            sb.append("\r\n");
            line(sb, e.getKey(), "impressions", "viewable", "clicks", "ctr", "viewability_rate", "uniques");
            for (Row r : e.getValue()) {
                line(sb, r.key(), r.impressions(), r.viewable(), r.clicks(), r.ctr(), r.viewabilityRate(), r.uniques());
            }
        }
        return sb.toString();
    }

    static void line(StringBuilder sb, Object... values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(cell(values[i]));
        }
        sb.append("\r\n");
    }

    static String cell(Object value) {
        if (value == null) {
            return "";
        }
        String v = String.valueOf(value);
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0 && !v.matches("-?\\d+(\\.\\d+)?")) {
            v = "'" + v;
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            v = "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    private LocalDate[] range(LocalDate from, LocalDate to, BannerCampaign campaign) {
        LocalDate today = clock.instant().atZone(zone).toLocalDate();
        LocalDate end = to == null ? today : to;
        LocalDate start = from;
        if (start == null) {
            start = campaign != null && campaign.getStart() != null ? campaign.getStart().atZone(zone).toLocalDate() : end.minusDays(29);
        }
        if (start.isAfter(end)) {
            start = end;
        }
        if (start.isBefore(end.minusDays(400))) {
            start = end.minusDays(400);
        }
        return new LocalDate[]{start, end};
    }

    private List<AnalyticsEvent> bannerEvents(Collection<String> campaignIds, LocalDate from, LocalDate to) {
        Query q = Query.query(Criteria.where("name").in(BannerStatsCalculator.IMPRESSION, BannerStatsCalculator.VIEWABLE, BannerStatsCalculator.CLICK)
                        .and("ts").gte(from.atStartOfDay(zone).toInstant()).lt(to.plusDays(1).atStartOfDay(zone).toInstant())
                        .and("props.campaignId").in(campaignIds))
                .with(Sort.by("ts"))
                .limit(maxEvents);
        return mongo.find(q, AnalyticsEvent.class, eventCollection);
    }

    private List<AnalyticsEvent> sessionEvents(List<AnalyticsEvent> bannerEvents, LocalDate from, LocalDate to) {
        Set<String> sids = new LinkedHashSet<>();
        for (AnalyticsEvent e : bannerEvents) {
            if (BannerStatsCalculator.CLICK.equals(e.getName()) && e.getSid() != null) {
                sids.add(e.getSid());
            }
        }
        if (sids.isEmpty()) {
            return List.of();
        }
        List<String> names = new ArrayList<>(conversionEvents);
        names.add("page_view");
        names.add("page_leave");
        List<AnalyticsEvent> out = new ArrayList<>();
        List<String> all = new ArrayList<>(sids);
        for (int i = 0; i < all.size(); i += 1000) {
            Query q = Query.query(Criteria.where("sid").in(all.subList(i, Math.min(all.size(), i + 1000)))
                    .and("name").in(names)
                    .and("ts").gte(from.atStartOfDay(zone).toInstant())
                    .lt(to.plusDays(1).atStartOfDay(zone).toInstant().plus(postClickWindow)))
                    .limit(maxEvents);
            out.addAll(mongo.find(q, AnalyticsEvent.class, eventCollection));
        }
        return out;
    }
}
