package com.naqqa.analytics;

import com.naqqa.analytics.model.AnalyticsEvent;
import com.naqqa.analytics.model.RollupRow;
import com.naqqa.analytics.query.AnalyticsDtos;
import com.naqqa.analytics.query.AnalyticsQuery;
import com.naqqa.analytics.query.Insights;
import com.naqqa.analytics.query.Reports;
import com.naqqa.analytics.query.SessionSummary;
import com.naqqa.analytics.rollup.Rollups;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.naqqa.analytics.AnalyticsTestSupport.entityEvent;
import static com.naqqa.analytics.AnalyticsTestSupport.event;
import static com.naqqa.analytics.AnalyticsTestSupport.props;
import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsReportsTest {

    private static final Instant T0 = Instant.parse("2026-10-01T08:00:00Z");

    private static Instant at(long minutes) {
        return T0.plusSeconds(minutes * 60);
    }

    private List<AnalyticsEvent> sample() {
        List<AnalyticsEvent> e = new ArrayList<>();
        e.add(event("page_view", "v1", "s1", at(0)).setPath("/ro/").setPageType("home"));
        e.add(event("page_leave", "v1", "s1", at(1)).setPath("/ro/").setProps(props("activeMs", 4_000, "scroll", 50)));
        e.add(event("page_view", "v2", "s2", at(2)).setPath("/ro/").setPageType("home").setNewVisitor(true));
        e.add(event("page_view", "v2", "s2", at(3)).setPath("/ro/promotions/a").setPageType("promotion").setNewVisitor(true));
        e.add(event("page_leave", "v2", "s2", at(4)).setPath("/ro/promotions/a").setProps(props("activeMs", 20_000, "scroll", 100)));
        e.add(event("page_view", "v3", "s3", at(5)).setPath("/ro/promotions/a").setPageType("promotion").setNewVisitor(false));
        e.add(entityEvent("share_click", "v3", "s3", at(6), "PROMOTION", "1", "8").setProps(props("channel", "whatsapp")));
        return e;
    }

    @Test
    void kpiEngagementBounceAndDurations() {
        AnalyticsDtos.Kpis k = Reports.kpis(sample(), 20);
        assertThat(k.visitors()).isEqualTo(3);
        assertThat(k.sessions()).isEqualTo(3);
        assertThat(k.pageViews()).isEqualTo(4);
        assertThat(k.views()).isEqualTo(4);
        assertThat(k.engagedSessions()).isEqualTo(2);
        assertThat(k.engagedViews()).isEqualTo(3);
        assertThat(k.engagementRate()).isEqualTo(0.6667);
        assertThat(k.bounceRate()).isEqualTo(0.3333);
        assertThat(k.viewsPerSession()).isEqualTo(1.33);
        assertThat(k.pagesPerSession()).isEqualTo(1.33);
        assertThat(k.avgActiveMs()).isEqualTo(8_000);
        assertThat(k.avgDurationMs()).isEqualTo(12_000);
        assertThat(k.avgSessionDurationMs()).isEqualTo(80_000);
        assertThat(k.newVisitors()).isEqualTo(1);
        assertThat(k.newUsers()).isEqualTo(1);
        assertThat(k.returningVisitors()).isEqualTo(1);
        assertThat(k.conversions().share()).isEqualTo(1);
        assertThat(k.conversions().total()).isEqualTo(1);
        assertThat(k.impressions()).isEqualTo(20);
    }

    @Test
    void engagedDefinition() {
        SessionSummary oneShortPage = SessionSummary.of(List.of(event("page_view", "a", "x", at(0)))).get(0);
        assertThat(oneShortPage.engaged()).isFalse();
        assertThat(oneShortPage.bounce()).isTrue();
        SessionSummary longRead = SessionSummary.of(List.of(event("page_view", "a", "y", at(0)),
                event("page_leave", "a", "y", at(1)).setProps(props("activeMs", 10_000)))).get(0);
        assertThat(longRead.engaged()).isTrue();
        SessionSummary converted = SessionSummary.of(List.of(event("page_view", "a", "z", at(0)), event("promocode_click", "a", "z", at(1))))
                .get(0);
        assertThat(converted.engaged()).isTrue();
    }

    @Test
    void seriesFillsEmptyBuckets() {
        AnalyticsQuery q = AnalyticsQuery.range(LocalDate.parse("2026-09-30"), LocalDate.parse("2026-10-02"), AnalyticsTestSupport.ZONE);
        List<AnalyticsDtos.SeriesPoint> s = Reports.series(sample(), q);
        assertThat(s).extracting(AnalyticsDtos.SeriesPoint::t).containsExactly("2026-09-30", "2026-10-01", "2026-10-02");
        assertThat(s.get(1).pageViews()).isEqualTo(4);
        assertThat(s.get(1).visitors()).isEqualTo(3);
        assertThat(s.get(0).pageViews()).isZero();
    }

    @Test
    void orderedFunnels() {
        List<AnalyticsEvent> e = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            e.add(event("item_view", "v" + i, "s" + i, at(i)));
        }
        for (int i = 0; i < 4; i++) {
            e.add(event("promocode_click", "v" + i, "s" + i, at(20 + i)));
        }
        e.add(event("promocode_purchase", "v0", "s0", at(40)));
        e.add(event("promocode_purchase", "v9", "s9", at(0).minusSeconds(60)));
        List<AnalyticsDtos.FunnelStep> f = Insights.funnel(e, List.of("item_view", "promocode_click", "promocode_purchase"), "session");
        assertThat(f).extracting(AnalyticsDtos.FunnelStep::count).containsExactly(10L, 4L, 1L);
        assertThat(f.get(1).rate()).isEqualTo(0.4);
        assertThat(f.get(2).rate()).isEqualTo(0.1);
        assertThat(f.get(1).dropoff()).isEqualTo(0.6);
        assertThat(f.get(2).dropoff()).isEqualTo(0.75);
        List<AnalyticsDtos.FunnelStep> typed = Insights.funnel(List.of(entityEvent("item_view", "a", "b", at(0), "BOOKLET", "1", "8")),
                List.of("item_view:PROMOTION"), "visitor");
        assertThat(typed.get(0).count()).isZero();
    }

    @Test
    void weeklyCohorts() {
        List<AnalyticsEvent> e = new ArrayList<>();
        Instant w1 = Instant.parse("2026-09-07T10:00:00Z");
        Instant w2 = w1.plusSeconds(7 * 86400);
        Instant w3 = w2.plusSeconds(7 * 86400);
        for (int i = 0; i < 10; i++) {
            e.add(event("page_view", "v" + i, "s" + i, w1));
        }
        for (int i = 0; i < 5; i++) {
            e.add(event("page_view", "v" + i, "t" + i, w2));
        }
        for (int i = 0; i < 2; i++) {
            e.add(event("page_view", "v" + i, "u" + i, w3));
        }
        e.add(event("page_view", "n1", "n1", w2));
        e.add(event("page_view", "c-cookieless", "c1", w2));
        List<AnalyticsDtos.Cohort> c = Insights.cohorts(e, LocalDate.parse("2026-09-07"), LocalDate.parse("2026-09-27"), 8,
                AnalyticsTestSupport.ZONE);
        assertThat(c).hasSize(2);
        assertThat(c.get(0).week()).isEqualTo("2026-09-07");
        assertThat(c.get(0).size()).isEqualTo(10);
        assertThat(c.get(0).counts()).containsExactly(10L, 5L, 2L);
        assertThat(c.get(0).retention()).containsExactly(1.0, 0.5, 0.2);
        assertThat(c.get(1).size()).isEqualTo(1);
        assertThat(c.get(1).counts()).containsExactly(1L, 0L);
    }

    @Test
    void kAnonymitySuppressesSmallSegments() {
        List<AnalyticsEvent> e = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            e.add(event("page_view", "m" + i, "s" + i, at(i)).setDevice("mobile"));
        }
        for (int i = 0; i < 3; i++) {
            e.add(event("page_view", "d" + i, "d" + i, at(i)).setDevice("desktop"));
        }
        e.add(event("page_view", "t0", "t0", at(1)).setDevice("tablet"));
        List<AnalyticsDtos.Seg> segs = Insights.segments(e, AnalyticsEvent::getDevice, 5, 0);
        assertThat(segs).hasSize(2);
        assertThat(segs.get(0)).isEqualTo(new AnalyticsDtos.Seg("mobile", 7L, 0.6364, false));
        assertThat(segs.get(1).insufficient()).isTrue();
        assertThat(segs.get(1).key()).isNull();
        assertThat(segs.get(1).visitors()).isNull();
        assertThat(Insights.segments(e, AnalyticsEvent::getDevice, 0, 0)).hasSize(3);
        List<AnalyticsDtos.HeatCell> heat = Insights.heatmap(e, AnalyticsTestSupport.ZONE, 5);
        assertThat(heat).hasSize(168);
        assertThat(heat).contains(new AnalyticsDtos.HeatCell(4, 11, 11, 11));
        List<AnalyticsDtos.HeatCell> small = Insights.heatmap(e.subList(7, 10), AnalyticsTestSupport.ZONE, 5);
        assertThat(small.stream().mapToLong(AnalyticsDtos.HeatCell::visitors).sum()).isZero();
        assertThat(small.stream().mapToLong(AnalyticsDtos.HeatCell::events).sum()).isZero();
    }

    @Test
    void realtimePerMinute() {
        long now = Instant.parse("2026-10-03T10:30:30Z").toEpochMilli();
        List<AnalyticsEvent> e = new ArrayList<>();
        e.add(event("page_view", "a", "s1", Instant.ofEpochMilli(now - 10_000)).setPath("/ro/").setCountry("MD"));
        e.add(event("page_view", "b", "s2", Instant.ofEpochMilli(now - 20_000)).setPath("/ro/").setCountry("MD"));
        e.add(event("page_view", "a", "s1", Instant.ofEpochMilli(now - 70_000)).setPath("/ro/x").setCountry("MD"));
        e.add(event("item_click", "c", "s3", Instant.ofEpochMilli(now - 125_000)).setPath("/ro/y").setCountry("RO"));
        e.add(event("page_view", "old", "s9", Instant.ofEpochMilli(now - 40 * 60_000L)).setPath("/ro/"));
        Insights.RealtimeResult r = Insights.realtime(e, now, 30, 0);
        assertThat(r.activeVisitors()).isEqualTo(3);
        assertThat(r.views()).isEqualTo(3);
        assertThat(r.perMinute()).hasSize(30);
        AnalyticsDtos.MinutePoint last = r.perMinute().get(29);
        assertThat(last.t()).isEqualTo(Instant.parse("2026-10-03T10:30:00Z").toEpochMilli());
        assertThat(last.views()).isEqualTo(2);
        assertThat(last.visitors()).isEqualTo(2);
        assertThat(r.perMinute().get(28).views()).isEqualTo(1);
        assertThat(r.perMinute().get(27).visitors()).isEqualTo(1);
        assertThat(r.perMinute().stream().mapToLong(AnalyticsDtos.MinutePoint::views).sum()).isEqualTo(3);
        assertThat(r.topPaths().get(0)).isEqualTo(new AnalyticsDtos.RealtimePath("/ro/", 2, 2));
        assertThat(r.byCountry().get(0)).isEqualTo(new AnalyticsDtos.KeyCount("MD", 2));
        Insights.RealtimeResult partner = Insights.realtime(e, now, 30, 5);
        assertThat(partner.activeVisitors()).isZero();
        assertThat(partner.perMinute()).hasSize(30);
    }

    @Test
    void flowsAndSearch() {
        List<SessionSummary> sessions = SessionSummary.of(sample());
        List<AnalyticsDtos.Flow> flows = Reports.flows(sessions, false, 100);
        assertThat(flows).contains(new AnalyticsDtos.Flow("(entrance)", "home", 2), new AnalyticsDtos.Flow("home", "promotion", 1),
                new AnalyticsDtos.Flow("promotion", "(exit)", 2));
        List<AnalyticsEvent> s = new ArrayList<>();
        s.add(event("search", "a", "s1", at(0)).setProps(props("q", "lapte", "results", 12)));
        s.add(event("search_result_click", "a", "s1", at(1)).setProps(props("q", "lapte")));
        s.add(event("search", "b", "s2", at(2)).setProps(props("q", "lapte", "results", 12)));
        s.add(event("search", "c", "s3", at(3)).setProps(props("q", "unicorn", "results", 0, "zero", true)));
        s.add(event("filter_apply", "c", "s3", at(4)).setProps(props("filters", Map.of("categoryId", "4", "priceMax", 50))));
        Insights.SearchResult r = Insights.search(s, 10);
        assertThat(r.totals().searches()).isEqualTo(3);
        assertThat(r.totals().zeroResultSearches()).isEqualTo(1);
        assertThat(r.totals().ctr()).isEqualTo(0.3333);
        assertThat(r.top().get(0)).isEqualTo(new AnalyticsDtos.SearchRow("lapte", 2, 2, 12.0, 1, 0.5));
        assertThat(r.zeroResults()).containsExactly(new AnalyticsDtos.ZeroRow("unicorn", 1, 1));
        assertThat(r.filters()).extracting(AnalyticsDtos.KeyCount::key).containsExactlyInAnyOrder("categoryId", "priceMax");
    }

    @Test
    void rollupMathMatchesRaw() {
        List<AnalyticsEvent> e = sample();
        e.add(event("page_view", "bot", "sb", at(9)).setBot(true));
        e.add(event("page_view", "staff", "ss", at(9)).setInternal(true));
        List<RollupRow> rows = Rollups.daily("2026-10-01", e, Instant.now());
        RollupRow site = Rollups.find(rows, Rollups.SITE, Map.of());
        assertThat(site.getCount()).isEqualTo(4);
        assertThat(site.getUniques()).isEqualTo(3);
        assertThat(site.getActiveMs()).isEqualTo(24_000);
        assertThat(Rollups.find(rows, Rollups.SITE_SESSIONS, Map.of()).getCount()).isEqualTo(3);
        RollupRow share = Rollups.find(rows, Rollups.ENTITY, Map.of("entityType", "PROMOTION", "entityId", "1", "name", "share_click",
                "companyId", "8"));
        assertThat(share.getCount()).isEqualTo(1);
        assertThat(Rollups.find(rows, Rollups.COMPANY, Map.of("companyId", "8", "name", "share_click")).getUniques()).isEqualTo(1);
        assertThat(Rollups.find(rows, Rollups.EVENT, Map.of("name", "page_view")).getCount()).isEqualTo(4);
    }
}
