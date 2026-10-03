package com.naqqa.analytics.banners;

import com.naqqa.analytics.banners.model.BannerCampaign;
import com.naqqa.analytics.banners.model.BannerCreative;
import com.naqqa.analytics.banners.model.BannerDestination;
import com.naqqa.analytics.banners.model.BannerPriority;
import com.naqqa.analytics.banners.security.BannerTokenService;
import com.naqqa.analytics.banners.service.BannerClickService;
import com.naqqa.analytics.banners.service.BannerClickService.ClickContext;
import com.naqqa.analytics.banners.service.BannerClickService.ClickResult;
import com.naqqa.analytics.banners.spi.BannerEventRecorder.BannerEvent;
import com.naqqa.analytics.banners.store.BannerCampaignCache;
import com.naqqa.analytics.banners.store.MemoryBannerCounters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static com.naqqa.analytics.banners.BannerFixtures.campaign;
import static com.naqqa.analytics.banners.BannerFixtures.creative;
import static org.assertj.core.api.Assertions.assertThat;

class BannerClickDedupTest {

    static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-07T10:00:00Z"));
    private final BannerTokenService tokens = new BannerTokenService("s3cret", Duration.ofDays(30));
    private final List<BannerEvent> events = new ArrayList<>();
    private BannerFixtures.FakeRepository repository;
    private MemoryBannerCounters counters;
    private BannerClickService service;

    @BeforeEach
    void setUp() {
        repository = new BannerFixtures.FakeRepository();
        BannerCampaign c = campaign("c1", BannerPriority.PAID, "home_side");
        c.setCompanyId("8");
        repository.campaigns.put("c1", c);
        BannerCreative cr = creative("cr1", "c1", 1);
        repository.creatives.put("cr1", cr);
        BannerCreative ext = creative("cr2", "c1", 1);
        ext.setDestination(new BannerDestination(BannerDestination.Type.EXTERNAL, null, "https://partner.md/oferta"));
        repository.creatives.put("cr2", ext);
        counters = new MemoryBannerCounters(clock, 10_000);
        service = new BannerClickService(tokens, repository, counters, new BannerCampaignCache(repository, clock, 30_000), () -> events::add,
                clock, Duration.ofSeconds(30), true, "/");
    }

    private String token(String creativeId, String vid) {
        return tokens.sign(new BannerTokenService.Claims("c1", creativeId, "home_side", "ro", vid, "sid-1", "b-" + vid, "home", clock.instant()));
    }

    private final ClickContext ctx = new ClickContext("Mozilla/5.0", "10.0.0.1", null);

    @Test
    void clicksWithin30SecondsAreDeduplicated() {
        counters.recordServe("v1", "c1", LocalDate.of(2026, 10, 7));
        String t = token("cr1", "v1");
        ClickResult first = service.click(t, ctx);
        assertThat(first.recorded()).isTrue();
        assertThat(first.location()).isEqualTo("/ro/promotions/x");
        clock.advance(Duration.ofSeconds(10));
        ClickResult second = service.click(t, ctx);
        assertThat(second.duplicate()).isTrue();
        assertThat(second.recorded()).isFalse();
        assertThat(second.location()).isEqualTo("/ro/promotions/x");
        clock.advance(Duration.ofSeconds(21));
        assertThat(service.click(t, ctx).recorded()).isTrue();
        assertThat(repository.clicks).isEqualTo(2);
        assertThat(events).hasSize(2);
        assertThat(events.get(0).name()).isEqualTo("banner_click");
        assertThat(events.get(0).bannerId()).isEqualTo("b-v1");
        assertThat(events.get(0).companyId()).isEqualTo("8");
        assertThat(events.get(0).noPriorImpression()).isFalse();
    }

    @Test
    void differentVisitorsAreNotDeduplicated() {
        assertThat(service.click(token("cr1", "v1"), ctx).recorded()).isTrue();
        assertThat(service.click(token("cr1", "v2"), ctx).recorded()).isTrue();
    }

    @Test
    void anonymousClicksDedupByIpAndAgent() {
        String t = token("cr1", null);
        assertThat(service.click(t, ctx).recorded()).isTrue();
        assertThat(service.click(t, ctx).duplicate()).isTrue();
        assertThat(service.click(t, new ClickContext("Mozilla/5.0", "10.0.0.2", null)).recorded()).isTrue();
    }

    @Test
    void clickWithoutServeIsFlagged() {
        service.click(token("cr1", "fresh"), ctx);
        assertThat(events.get(0).noPriorImpression()).isTrue();
    }

    @Test
    void externalDestinationAndInvalidTokens() {
        assertThat(service.click(token("cr2", "v9"), ctx).location()).isEqualTo("https://partner.md/oferta");
        ClickResult bad = service.click("nope", ctx);
        assertThat(bad.valid()).isFalse();
        assertThat(bad.location()).isEqualTo("/");
        String foreign = tokens.sign(new BannerTokenService.Claims("other", "cr1", "home_side", "ro", "v", null, null, null, clock.instant()));
        assertThat(service.click(foreign, ctx).valid()).isFalse();
    }

    @Test
    void memoryCountersFrequencyAndExpiry() {
        LocalDate day = LocalDate.of(2026, 10, 7);
        counters.recordServe("v", "c1", day);
        counters.recordServe("v", "c1", day);
        assertThat(counters.servedToday("v", "c1", day)).isEqualTo(2);
        assertThat(counters.servedToday("v", "c1", day.plusDays(1))).isZero();
        assertThat(counters.seen("v", "c1")).isTrue();
        clock.advance(Duration.ofHours(25));
        assertThat(counters.seen("v", "c1")).isFalse();
    }
}
