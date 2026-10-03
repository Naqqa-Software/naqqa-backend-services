package com.naqqa.analytics;

import com.naqqa.analytics.AnalyticsTestSupport.Harness;
import com.naqqa.analytics.collect.RealtimeCounters;
import com.naqqa.analytics.model.AnalyticsEvent;
import com.naqqa.analytics.model.RollupRow;
import com.naqqa.analytics.query.AnalyticsDtos;
import com.naqqa.analytics.query.AnalyticsQuery;
import com.naqqa.analytics.query.AnalyticsQueryService;
import com.naqqa.analytics.rollup.Rollups;
import com.naqqa.analytics.spi.AnalyticsEntityResolver.EntityInfo;
import com.naqqa.analytics.web.QueryParser;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.naqqa.analytics.AnalyticsTestSupport.envelope;
import static com.naqqa.analytics.AnalyticsTestSupport.ev;
import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsSyntheticTrafficTest {

    private static final String BOT_UA = "Mozilla/5.0 (compatible; bingbot/2.0; +http://www.bing.com/bingbot.htm)";
    private static final String X = "{\"entityType\":\"PROMOTION\",\"entityId\":\"X\",\"sourceBlock\":\"home_promotions_carousel\",\"position\":%d}";
    private static final String Y = "{\"entityType\":\"PROMOTION\",\"entityId\":\"Y\",\"sourceBlock\":\"listing\",\"position\":1}";

    @Test
    void thousandVisitorsThreeThousandViewsHundredFiftyClicks() {
        Harness h = new Harness(Instant.parse("2026-10-02T06:00:00Z"), AnalyticsTestSupport.STAFF_IS_INTERNAL);
        h.properties.getRateLimit().setEnabled(false);
        h.entities.put("PROMOTION:X", new EntityInfo("8", "4", "Promo X", false));
        h.entities.put("PROMOTION:Y", new EntityInfo("9", "4", "Promo Y", false));
        for (int i = 0; i < 1000; i++) {
            h.clock.advance(10_000L);
            long t = h.now();
            String vid = String.format("visitor-%05d", i);
            String sid = String.format("session-%05d", i);
            String ip = "93.115." + (i / 250) + "." + (i % 250);
            String ua = i % 3 == 0 ? AnalyticsTestSupport.IPHONE : AnalyticsTestSupport.CHROME;
            String impression = ev("item_impression", t - 60_000L, String.format(X, i % 12));
            String clicks = i < 150 ? ev("item_click", t - 45_000L, String.format(X, i % 12)) : ev("item_click", t - 45_000L, Y);
            h.send(envelope(vid, sid, "full", null, ev("page_view", t - 60_000L, null), impression, ev("page_view", t - 40_000L, null), clicks,
                    ev("page_view", t - 20_000L, null)), ip, ua, null);
        }
        for (int b = 0; b < 10; b++) {
            h.clock.advance(1_000L);
            long t = h.now();
            h.send(envelope("botvisit-" + b, "botsess-" + b, "full", null, ev("page_view", t - 50_000L, null), ev("page_view", t - 40_000L, null),
                    ev("page_view", t - 30_000L, null), ev("page_view", t - 20_000L, null), ev("page_view", t - 10_000L, null),
                    ev("item_click", t - 5_000L, String.format(X, 0))), "8.8.8." + b, BOT_UA, null);
        }
        for (int s = 0; s < 5; s++) {
            h.clock.advance(1_000L);
            long t = h.now();
            h.send(envelope("staffvisit-" + s, "staffsess-" + s, "full", null, ev("page_view", t - 40_000L, null), ev("page_view", t - 30_000L, null),
                    ev("page_view", t - 20_000L, null), ev("page_view", t - 10_000L, null), ev("item_click", t - 5_000L, String.format(X, 0))),
                    "93.116.0." + s, AnalyticsTestSupport.CHROME, AnalyticsTestSupport.auth("rep" + s, "ROLE_REPRESENTATIVE"));
        }
        assertThat(h.events).hasSize(1000 * 5 + 10 * 6 + 5 * 5);
        assertThat(h.events.stream().filter(AnalyticsEvent::isBot).count()).isEqualTo(60);
        assertThat(h.events.stream().filter(AnalyticsEvent::isInternal).count()).isEqualTo(25);

        h.clock.set(Instant.parse("2026-10-03T09:00:00Z"));
        AnalyticsQueryService queries = AnalyticsTestSupport.queries(h.events, h.clock);
        AnalyticsQuery q = QueryParser.parse(Map.of("from", "2026-10-02", "to", "2026-10-02"), AnalyticsTestSupport.ZONE, h.clock, 400, true);

        AnalyticsDtos.Overview overview = queries.overview(q);
        assertThat(overview.kpis().visitors()).isEqualTo(1000);
        assertThat(overview.kpis().newVisitors()).isEqualTo(1000);
        assertThat(overview.kpis().sessions()).isEqualTo(1000);
        assertThat(overview.kpis().pageViews()).isEqualTo(3000);
        assertThat(overview.kpis().impressions()).isEqualTo(1000);
        assertThat(overview.kpis().clicks()).isEqualTo(1000);
        assertThat(overview.kpis().viewsPerSession()).isEqualTo(3.0);
        assertThat(overview.kpis().engagedSessions()).isEqualTo(1000);
        assertThat(overview.kpis().bounceRate()).isZero();
        assertThat(overview.series()).hasSize(1);
        assertThat(overview.series().get(0).pageViews()).isEqualTo(3000);
        assertThat(overview.byDevice()).extracting(AnalyticsDtos.Seg::key, AnalyticsDtos.Seg::visitors)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("desktop", 666L), org.assertj.core.groups.Tuple.tuple("mobile", 334L));

        AnalyticsDtos.Content content = queries.content(q.withFilters(Map.of("entityType", "PROMOTION")));
        AnalyticsDtos.ContentItem x = content.items().stream().filter(i -> "X".equals(i.entityId())).findFirst().orElseThrow();
        assertThat(x.clicks()).isEqualTo(150);
        assertThat(x.impressions()).isEqualTo(1000);
        assertThat(x.reach()).isEqualTo(1000);
        assertThat(x.uniques()).isEqualTo(150);
        assertThat(x.ctr()).isEqualTo(0.15);
        assertThat(x.companyId()).isEqualTo("8");
        assertThat(x.sources()).containsExactly(new AnalyticsDtos.KeyCount("home_promotions_carousel", 150));
        AnalyticsDtos.ContentItem y = content.items().stream().filter(i -> "Y".equals(i.entityId())).findFirst().orElseThrow();
        assertThat(y.clicks()).isEqualTo(850);

        AnalyticsDtos.Companies companies = queries.companies(q);
        assertThat(companies.companies().stream().mapToLong(AnalyticsDtos.CompanyRow::clicks).sum()).isEqualTo(overview.kpis().clicks());
        assertThat(companies.companies().stream().mapToLong(AnalyticsDtos.CompanyRow::impressions).sum()).isEqualTo(overview.kpis().impressions());

        AnalyticsDtos.PartnerOverview partner = queries.partnerOverview(q.withCompanyIds(Set.of("8")));
        assertThat(partner.kpis().clicks()).isEqualTo(150);
        assertThat(partner.kpis().impressions()).isEqualTo(1000);
        assertThat(partner.kpis().reach()).isEqualTo(1000);
        assertThat(partner.kpis().visitors()).isEqualTo(150);

        AnalyticsDtos.Funnel funnel = queries.funnel(q, List.of("page_view", "item_click"), "visitor");
        assertThat(funnel.steps().get(1).count()).isEqualTo(1000);

        AnalyticsQuery withBots = QueryParser.parse(Map.of("from", "2026-10-02", "to", "2026-10-02", "includeBots", "true", "includeInternal", "true"),
                AnalyticsTestSupport.ZONE, h.clock, 400, true);
        assertThat(queries.overview(withBots).kpis().pageViews()).isEqualTo(3000 + 50 + 20);

        List<RollupRow> rows = Rollups.daily("2026-10-02", h.events, Instant.now());
        RollupRow site = Rollups.find(rows, Rollups.SITE, Map.of());
        assertThat(site.getCount()).isEqualTo(overview.kpis().pageViews());
        assertThat(site.getUniques()).isEqualTo(overview.kpis().visitors());
        assertThat(Rollups.find(rows, Rollups.SITE_SESSIONS, Map.of()).getCount()).isEqualTo(overview.kpis().sessions());
        assertThat(Rollups.find(rows, Rollups.ENTITY, Map.of("entityType", "PROMOTION", "entityId", "X", "name", "item_click", "companyId", "8"))
                .getCount()).isEqualTo(150);

        assertThat(new RealtimeCounters(h.kv, "t:").visitors("2026-10-02")).isEqualTo(1000);
    }
}
