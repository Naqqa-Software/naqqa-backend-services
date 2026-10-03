package com.naqqa.analytics.banners;

import com.naqqa.analytics.banners.service.BannerStatsCalculator;
import com.naqqa.analytics.banners.service.BannerStatsCalculator.Row;
import com.naqqa.analytics.banners.service.BannerStatsCalculator.Stats;
import com.naqqa.analytics.model.AnalyticsEvent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BannerStatsCalculatorTest {

    private final BannerStatsCalculator calc = new BannerStatsCalculator(ZoneId.of("Europe/Chisinau"), Duration.ofMinutes(30),
            List.of("promocode_purchase", "share_click", "add_to_list"));
    private final Instant t0 = Instant.parse("2026-10-07T09:00:00Z");

    private AnalyticsEvent banner(String name, String bannerId, String vid, String sid, String creative, String slot, String device, long offsetSec) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("bannerId", bannerId);
        props.put("campaignId", "c1");
        props.put("creativeId", creative);
        props.put("slot", slot);
        if (name.equals("banner_viewable")) {
            props.put("visibleMs", 1500);
        }
        return new AnalyticsEvent().setName(name).setTs(t0.plusSeconds(offsetSec)).setVid(vid).setSid(sid).setDevice(device)
                .setLang("ro").setPageType("home").setProps(props);
    }

    private AnalyticsEvent session(String name, String sid, long offsetSec) {
        return new AnalyticsEvent().setName(name).setSid(sid).setTs(t0.plusSeconds(offsetSec));
    }

    @Test
    void syntheticTrafficProducesExactNumbers() {
        List<AnalyticsEvent> events = new ArrayList<>();
        List<AnalyticsEvent> sessions = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            String vid = "v" + (i % 40);
            String sid = "s" + i;
            String creative = i % 2 == 0 ? "A" : "B";
            String device = i % 4 == 0 ? "mobile" : "desktop";
            String slot = i < 60 ? "home_between_1" : "listing_top";
            String bid = "b" + i;
            events.add(banner("banner_impression", bid, vid, sid, creative, slot, device, i * 10L));
            if (i % 5 != 0) {
                events.add(banner("banner_viewable", bid, vid, sid, creative, slot, device, i * 10L + 2));
                events.add(banner("banner_viewable", bid, vid, sid, creative, slot, device, i * 10L + 3));
            }
            if (i % 10 == 0) {
                events.add(banner("banner_click", bid, vid, sid, creative, slot, device, i * 10L + 5));
                events.add(banner("banner_click", bid, vid, sid, creative, slot, device, i * 10L + 6));
                sessions.add(session("page_view", sid, i * 10L + 7));
                sessions.add(session("page_view", sid, i * 10L + 8));
                if (i % 20 == 0) {
                    sessions.add(session("promocode_purchase", sid, i * 10L + 9));
                }
                sessions.add(session("share_click", sid, i * 10L + 3600));
            }
        }
        events.add(banner("banner_click", "ghost", "vx", "sx", "A", "home_between_1", "desktop", 5000));
        events.add(banner("banner_impression", "bot1", "bot", "sb", "A", "home_between_1", "desktop", 10).setBot(true));
        events.add(banner("banner_impression", "int1", "adm", "si", "A", "home_between_1", "desktop", 10).setInternal(true));

        Stats s = calc.compute(events, sessions);

        assertThat(s.totals().impressions()).isEqualTo(100);
        assertThat(s.totals().viewable()).isEqualTo(80);
        assertThat(s.totals().viewabilityRate()).isEqualTo(0.8);
        assertThat(s.totals().clicks()).isEqualTo(11);
        assertThat(s.totals().suspectClicks()).isEqualTo(1);
        assertThat(s.totals().ctr()).isEqualTo(0.11);
        assertThat(s.totals().uniques()).isEqualTo(40);
        assertThat(s.totals().frequency()).isEqualTo(2.5);
        assertThat(s.totals().avgVisibleMs()).isEqualTo(1500);

        Map<String, Row> creatives = byKey(s.breakdowns().get("creativeId"));
        assertThat(creatives.get("A").impressions()).isEqualTo(50);
        assertThat(creatives.get("B").impressions()).isEqualTo(50);
        assertThat(creatives.get("A").clicks()).isEqualTo(11);
        assertThat(creatives.get("B").clicks()).isZero();

        Map<String, Row> slots = byKey(s.breakdowns().get("slot"));
        assertThat(slots.get("home_between_1").impressions()).isEqualTo(60);
        assertThat(slots.get("listing_top").impressions()).isEqualTo(40);
        assertThat(slots.get("home_between_1").clicks()).isEqualTo(7);
        assertThat(slots.get("listing_top").clicks()).isEqualTo(4);

        Map<String, Row> devices = byKey(s.breakdowns().get("device"));
        assertThat(devices.get("mobile").impressions()).isEqualTo(25);
        assertThat(devices.get("desktop").impressions()).isEqualTo(75);

        Map<String, Row> hours = byKey(s.breakdowns().get("hour"));
        assertThat(hours.get("12").impressions()).isEqualTo(100);

        assertThat(s.series()).hasSize(1);
        assertThat(s.series().get(0).day()).isEqualTo("2026-10-07");
        assertThat(s.series().get(0).impressions()).isEqualTo(100);

        assertThat(s.postClick().sessions()).isEqualTo(10);
        assertThat(s.postClick().pageViews()).isEqualTo(20);
        assertThat(s.postClick().conversions()).isEqualTo(5);
        assertThat(s.postClick().byEvent()).containsEntry("promocode_purchase", 5L).doesNotContainKey("share_click");
        assertThat(s.postClick().conversionRate()).isEqualTo(0.4545);
    }

    @Test
    void viewMatchesDashboardShape() {
        List<AnalyticsEvent> events = List.of(
                banner("banner_impression", "b1", "v1", "s1", "A", "home_side", "desktop", 0),
                banner("banner_click", "b1", "v1", "s1", "A", "home_side", "desktop", 5));
        Map<String, Object> view = com.naqqa.analytics.banners.service.BannerStatsService.view(calc.compute(events, List.of()), "2026-10-01", "2026-10-07");
        assertThat(view).containsKeys("totals", "series", "bySlot", "byCreative", "byCampaign", "byDevice", "byHour", "postClick");
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) view.get("totals");
        assertThat(totals).containsEntry("impressions", 1L).containsEntry("clicks", 1L).containsEntry("ctr", 1.0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> series = (List<Map<String, Object>>) view.get("series");
        assertThat(series.get(0)).containsEntry("t", "2026-10-07");
    }

    @Test
    void emptyInput() {
        Stats s = calc.compute(List.of(), List.of());
        assertThat(s.totals().impressions()).isZero();
        assertThat(s.totals().ctr()).isZero();
        assertThat(s.series()).isEmpty();
        assertThat(s.postClick().conversions()).isZero();
    }

    private static Map<String, Row> byKey(List<Row> rows) {
        Map<String, Row> out = new LinkedHashMap<>();
        rows.forEach(r -> out.put(r.key(), r));
        return out;
    }
}
