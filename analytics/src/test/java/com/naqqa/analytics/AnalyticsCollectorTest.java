package com.naqqa.analytics;

import com.naqqa.analytics.AnalyticsTestSupport.Harness;
import com.naqqa.analytics.collect.AnalyticsIngest;
import com.naqqa.analytics.collect.BotDetector;
import com.naqqa.analytics.collect.CollectorService.CollectResult;
import com.naqqa.analytics.collect.EntityLookup;
import com.naqqa.analytics.collect.QualityCounters;
import com.naqqa.analytics.collect.Sessionizer;
import com.naqqa.analytics.model.AnalyticsEvent;
import com.naqqa.analytics.spi.AnalyticsEntityResolver.EntityInfo;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.naqqa.analytics.AnalyticsTestSupport.envelope;
import static com.naqqa.analytics.AnalyticsTestSupport.ev;
import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsCollectorTest {

    private static final Instant START = Instant.parse("2026-10-03T09:00:00Z");
    private static final String CTX_GOOGLE = "{\"lang\":\"ro\",\"path\":\"/ro/promotions/x?email=a@b.md\",\"pageType\":\"promotion\","
            + "\"ref\":\"https://www.google.com/\",\"utm\":{\"source\":\"\",\"medium\":\"\",\"campaign\":\"\"},\"screen\":\"390x844\"}";

    @Test
    void acceptsValidBatchAndEnriches() {
        Harness h = new Harness(START);
        h.entities.put("PROMOTION:123", new EntityInfo("8", "4", "Lapte 2+1", false));
        long t = h.now();
        CollectResult r = h.send(envelope("vid-000001", "sid-000001", "full", CTX_GOOGLE,
                ev("page_view", t - 1000, null),
                ev("item_click", t - 500, "{\"entityType\":\"PROMOTION\",\"entityId\":\"123\",\"companyId\":\"999\",\"position\":3,\"sourceBlock\":\"related\"}"),
                ev("nope_event", t, null)), "93.115.10.25", AnalyticsTestSupport.IPHONE, null);
        assertThat(r.status()).isEqualTo(202);
        assertThat(r.accepted()).isEqualTo(2);
        assertThat(r.rejected()).isEqualTo(1);
        assertThat(h.events).hasSize(2);
        AnalyticsEvent click = h.events.get(1);
        assertThat(click.getCompanyId()).isEqualTo("8");
        assertThat(click.getCategoryId()).isEqualTo("4");
        assertThat(click.getPosition()).isEqualTo(3);
        assertThat(click.getChannel()).isEqualTo("Organic");
        assertThat(click.getSource()).isEqualTo("google");
        assertThat(click.getReferrer()).isEqualTo("google.com");
        assertThat(click.getDevice()).isEqualTo("mobile");
        assertThat(click.getOs()).isEqualTo("iOS");
        assertThat(click.getPath()).isEqualTo("/ro/promotions/x");
        assertThat(click.getLanding()).isEqualTo("/ro/promotions/x");
        assertThat(click.getVid()).isEqualTo("vid-000001");
        assertThat(click.getSid()).isEqualTo("sid-000001");
        assertThat(click.getNewVisitor()).isTrue();
        assertThat(click.getIp()).isNull();
        assertThat(click.isBot()).isFalse();
        assertThat(h.quality.totals()).containsEntry("unknown_event", 1L);
    }

    @Test
    void cookielessConsentHashesVisitorAndDropsUser() {
        Harness h = new Harness(START, AnalyticsTestSupport.STAFF_IS_INTERNAL);
        h.send(envelope("vid-000001", "sid-000002", "cookieless", null, ev("page_view", h.now(), null)), "93.115.10.25",
                AnalyticsTestSupport.CHROME, AnalyticsTestSupport.auth("42", "ROLE_END_USER"));
        AnalyticsEvent e = h.events.get(0);
        assertThat(e.getVid()).startsWith("c-").isNotEqualTo("vid-000001");
        assertThat(e.getUid()).isNull();
        assertThat(e.getConsent()).isEqualTo("cookieless");
        assertThat(e.getNewVisitor()).isNull();
        assertThat(e.isInternal()).isFalse();
        assertThat(e.isLoggedIn()).isTrue();
    }

    @Test
    void staffTrafficIsInternalAndTestTitlesFlagged() {
        Harness h = new Harness(START, AnalyticsTestSupport.STAFF_IS_INTERNAL);
        h.entities.put("PROMOTION:7", new EntityInfo("8", null, "QA admb PROMO-A", false));
        h.send(envelope("vid-000003", "sid-000003", "full", null,
                ev("item_view", h.now(), "{\"entityType\":\"PROMOTION\",\"entityId\":\"7\"}")), "93.115.10.25", AnalyticsTestSupport.CHROME,
                AnalyticsTestSupport.auth("5", "ROLE_REPRESENTATIVE"));
        AnalyticsEvent e = h.events.get(0);
        assertThat(e.isInternal()).isTrue();
        assertThat(e.isTest()).isTrue();
        assertThat(e.getUid()).isEqualTo("5");
    }

    @Test
    void botsAreKeptButFlagged() {
        Harness h = new Harness(START);
        h.send(envelope("vid-000004", "sid-000004", "full", null, ev("page_view", h.now(), null)), "93.115.10.25",
                "Mozilla/5.0 (compatible; bingbot/2.0)", null);
        assertThat(h.events).hasSize(1);
        assertThat(h.events.get(0).isBot()).isTrue();
        assertThat(h.events.get(0).getBotReason()).isEqualTo(BotDetector.UA);
        Harness fast = new Harness(START);
        long t = fast.now();
        fast.send(envelope("vid-000005", "sid-000005", "full", null, ev("page_view", t - 400, null), ev("page_view", t - 300, null),
                ev("page_view", t - 200, null), ev("page_view", t - 100, null), ev("page_view", t, null)));
        assertThat(fast.events).allMatch(AnalyticsEvent::isBot);
        assertThat(fast.events.get(0).getBotReason()).isEqualTo(BotDetector.TOO_FAST);
    }

    @Test
    void limitsAndRateLimiting() {
        Harness h = new Harness(START);
        assertThat(h.send("x".repeat(70_000)).status()).isEqualTo(413);
        assertThat(h.send("{not json").status()).isEqualTo(400);
        assertThat(h.send("{\"v\":1,\"sid\":\"no\",\"events\":[]}").status()).isEqualTo(400);
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 55; i++) {
            many.add(ev("click", h.now() - 60_000L + i * 1000L, "{\"target\":\"b\"}"));
        }
        CollectResult r = h.send(envelope("vid-000006", "sid-000006", "full", null, many.toArray(String[]::new)));
        assertThat(r.accepted()).isEqualTo(50);
        assertThat(r.rejected()).isEqualTo(5);
        assertThat(h.send(envelope("vid-000006", "sid-000006", "full", null, ev("page_view", h.now() + 3_600_000L, null))).rejected()).isEqualTo(1);
        Harness fresh = new Harness(START);
        CollectResult last = null;
        for (int i = 0; i < 125; i++) {
            last = fresh.send(envelope("vid-000007", "sid-000007", "full", null, ev("click", fresh.now(), null)));
        }
        assertThat(last.status()).isEqualTo(429);
        assertThat(last.retryAfterSeconds()).isPositive();
        assertThat(fresh.quality.totals().get(QualityCounters.RATE_LIMITED)).isPositive();
    }

    @Test
    void sessionRotatesAfterInactivityAndCampaignChange() {
        Harness h = new Harness(START);
        h.send(envelope("vid-000008", "sid-000008", "full", null, ev("page_view", h.now(), null)));
        h.clock.advance(31 * 60_000L);
        h.send(envelope("vid-000008", "sid-000008", "full", null, ev("page_view", h.now(), null)));
        h.clock.advance(60_000L);
        h.send(envelope("vid-000008", "sid-000008", "full", CTX_GOOGLE, ev("page_view", h.now(), null)));
        assertThat(h.events).extracting(AnalyticsEvent::getSid).doesNotHaveDuplicates();
        assertThat(h.events.get(0).getSid()).isEqualTo("sid-000008");
        assertThat(h.events.get(1).getSid()).startsWith("sid-000008.");
        assertThat(h.events.get(1).getNewVisitor()).isFalse();
        assertThat(h.events.get(2).getChannel()).isEqualTo("Organic");
        h.clock.advance(60_000L);
        h.send(envelope("vid-000008", "sid-000008", "full", null, ev("page_view", h.now(), null)));
        assertThat(h.events.get(3).getSid()).isEqualTo(h.events.get(2).getSid());
        assertThat(h.events.get(3).getChannel()).isEqualTo("Organic");
    }

    @Test
    void serverIngestJoinsSiteSession() {
        Harness h = new Harness(START);
        h.send(envelope("vid-000009", "sid-000009", "full", CTX_GOOGLE, ev("page_view", h.now(), null)));
        List<AnalyticsEvent> sink = new ArrayList<>();
        AnalyticsIngest ingest = new AnalyticsIngest(AnalyticsTestSupport.validator(), sessionCacheOf(h), new Sessionizer(1_800_000L,
                AnalyticsTestSupport.ZONE), new EntityLookup(null, List.of(), 0, 10, h.clock), BotDetector.defaults(), sink::addAll, null,
                new QualityCounters(), h.clock);
        assertThat(ingest.track(AnalyticsIngest.ServerEvent.of("chat_bot_reply", null, "sid-000009",
                AnalyticsTestSupport.props("latencyMs", 420, "intent", "promo_search", "llm", true)))).isTrue();
        assertThat(sink).hasSize(1);
        assertThat(sink.get(0).getVid()).isEqualTo("vid-000009");
        assertThat(sink.get(0).getChannel()).isEqualTo("Organic");
        assertThat(sink.get(0).getLanding()).isEqualTo("/ro/promotions/x");
        assertThat(ingest.track(AnalyticsIngest.ServerEvent.of("not_registered", null, "sid-000009", null))).isFalse();
    }

    @Test
    void pixelTracksCookielessPageView() {
        Harness h = new Harness(START);
        CollectResult r = h.collector.trackCookieless("page_view", null, "/ro/blog/x", null, "https://t.me/omy", "ru",
                new com.naqqa.analytics.collect.CollectorService.CollectRequest(new byte[0], "93.115.10.25", AnalyticsTestSupport.CHROME, null, "omy.md"),
                true);
        assertThat(r.status()).isEqualTo(202);
        AnalyticsEvent e = h.events.get(0);
        assertThat(e.getVid()).startsWith("c-");
        assertThat(e.getSid()).startsWith("px");
        assertThat(e.getChannel()).isEqualTo("Social");
        assertThat(e.getLang()).isEqualTo("ru");
    }

    private static com.naqqa.analytics.collect.SessionCache sessionCacheOf(Harness h) {
        com.naqqa.analytics.collect.SessionCache cache = new com.naqqa.analytics.collect.SessionCache(100, null, v -> true);
        for (com.naqqa.analytics.collect.SessionState s : h.sessions) {
            cache.put(s.clientSid(), s);
        }
        return cache;
    }
}
