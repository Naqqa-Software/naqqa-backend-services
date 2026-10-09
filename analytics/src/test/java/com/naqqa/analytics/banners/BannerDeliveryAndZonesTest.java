package com.naqqa.analytics.banners;

import com.naqqa.analytics.banners.engine.BannerPacingCalculator;
import com.naqqa.analytics.banners.engine.BannerSelector;
import com.naqqa.analytics.banners.engine.BannerTargetingEngine;
import com.naqqa.analytics.banners.model.BannerCampaign;
import com.naqqa.analytics.banners.model.BannerCreative;
import com.naqqa.analytics.banners.model.BannerImage;
import com.naqqa.analytics.banners.model.BannerPriority;
import com.naqqa.analytics.banners.model.BannerStatus;
import com.naqqa.analytics.banners.security.BannerTokenService;
import com.naqqa.analytics.banners.service.BannerClickService;
import com.naqqa.analytics.banners.service.BannerDeliveryService;
import com.naqqa.analytics.banners.service.BannerZoneService;
import com.naqqa.analytics.banners.store.BannerCampaignCache;
import com.naqqa.analytics.banners.store.BannerImpressionBuffer;
import com.naqqa.analytics.banners.store.MemoryBannerCounters;
import com.naqqa.analytics.banners.web.BannerDtos.ServeDto;
import com.naqqa.analytics.banners.web.BannerDtos.ZoneDto;
import com.naqqa.analytics.banners.web.BannerDtos.ZoneRowDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import static com.naqqa.analytics.banners.BannerFixtures.NOW;
import static com.naqqa.analytics.banners.BannerFixtures.campaign;
import static com.naqqa.analytics.banners.BannerFixtures.creative;
import static com.naqqa.analytics.banners.BannerFixtures.request;
import static org.assertj.core.api.Assertions.assertThat;

class BannerDeliveryAndZonesTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Chisinau");
    private static final LocalDate DAY = NOW.atZone(ZONE).toLocalDate();

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final BannerSelector selector = new BannerSelector(new BannerTargetingEngine(ZONE), new BannerPacingCalculator(), true);
    private final BannerTokenService tokens = new BannerTokenService("s3cret", Duration.ofDays(30));
    private BannerFixtures.FakeRepository repository;
    private MemoryBannerCounters counters;
    private BannerCampaignCache cache;
    private BannerImpressionBuffer buffer;
    private BannerDeliveryService delivery;

    @BeforeEach
    void setUp() {
        repository = new BannerFixtures.FakeRepository();
        counters = new MemoryBannerCounters(clock, 100_000);
        cache = new BannerCampaignCache(repository, clock, 30_000);
        buffer = new BannerImpressionBuffer(repository, 60_000);
        delivery = new BannerDeliveryService(cache, selector, counters, repository, tokens, ZONE, "/api/t/b", new SplittableRandom(3), null,
                buffer, true);
    }

    private BannerCampaign add(String id, BannerPriority priority, String slot, String... creativeIds) {
        BannerCampaign c = campaign(id, priority, slot);
        repository.campaigns.put(id, c);
        for (String cr : creativeIds) {
            BannerCreative creative = creative(cr, id, 1);
            creative.setDesktop(new BannerImage("/assets/x.svg#omy-tpl=promo", 1200, 150));
            repository.creatives.put(cr, creative);
        }
        cache.invalidate();
        return c;
    }

    @Test
    void emptySlotServesNothing() {
        assertThat(delivery.serve(request("home_between_1").build())).isNull();
        add("expired", BannerPriority.INTERNAL, "home_between_1", "e1").setEnd(NOW.minusSeconds(60));
        cache.invalidate();
        assertThat(delivery.serve(request("home_between_1").build())).isNull();
    }

    @Test
    void houseBannerFillsAnOtherwiseEmptySlot() {
        add("house", BannerPriority.FALLBACK, "home_between_1", "h1");
        ServeDto dto = delivery.serve(request("home_between_1").build());
        assertThat(dto).isNotNull();
        assertThat(dto.campaignId()).isEqualTo("house");
        assertThat(dto.slot()).isEqualTo("home_between_1");
        assertThat(dto.paid()).isFalse();
        add("internal", BannerPriority.INTERNAL, "home_between_1", "i1");
        for (int i = 0; i < 50; i++) {
            assertThat(delivery.serve(request("home_between_1").vid("v" + i).build()).campaignId()).isEqualTo("internal");
        }
    }

    @Test
    void servingIncrementsRotationAndBufferedImpressionCounters() {
        add("a", BannerPriority.INTERNAL, "home_between_1", "a1");
        add("b", BannerPriority.INTERNAL, "home_between_1", "b1");
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 1_000; i++) {
            counts.merge(delivery.serve(request("home_between_1").vid("v" + (i % 37)).build()).campaignId(), 1, Integer::sum);
        }
        assertThat(Math.abs(counts.get("a") - counts.get("b"))).isLessThanOrEqualTo(2);
        Map<String, Long> rotation = counters.rotation("home_between_1", DAY);
        assertThat(rotation.get("a") + rotation.get("b")).isEqualTo(1_000L);
        assertThat(rotation.get(BannerSelector.creativeKey("a1"))).isEqualTo((long) counts.get("a"));
        assertThat(repository.served).isZero();
        assertThat(buffer.pending(new BannerImpressionBuffer.Key("a", "a1", "home_between_1"))).isEqualTo((long) counts.get("a"));
        assertThat(buffer.flush()).isEqualTo(2);
        assertThat(repository.served).isEqualTo(1_000);
        assertThat(repository.campaigns.get("a").getSlotServed().get("home_between_1")).isEqualTo((long) counts.get("a"));
        assertThat(repository.creatives.get("b1").getSlotServed().get("home_between_1")).isEqualTo((long) counts.get("b"));
        assertThat(buffer.flush()).isZero();
    }

    @Test
    void directBufferWritesImmediately() {
        BannerImpressionBuffer direct = BannerImpressionBuffer.direct(repository);
        add("a", BannerPriority.INTERNAL, "home_side", "a1");
        direct.record("a", "a1", "home_side");
        direct.record("a", "a1", "home_side");
        assertThat(repository.campaigns.get("a").getServedImpressions()).isEqualTo(2);
        assertThat(repository.creatives.get("a1").getServed()).isEqualTo(2);
    }

    @Test
    void memoryRotationCountersAccumulateAndDropOldDays() {
        counters.recordRotation("home_side", DAY.minusDays(3), "old");
        counters.recordRotation("home_side", DAY, "x", "x", "y");
        counters.recordRotation("home_side", DAY, Map.of("y", 5L));
        assertThat(counters.rotation("home_side", DAY)).containsEntry("x", 2L).containsEntry("y", 6L);
        assertThat(counters.rotation("home_side", DAY.minusDays(3))).isEmpty();
        assertThat(counters.rotation("listing_top", DAY)).isEmpty();
    }

    @Test
    void clicksAreCountedPerSlotAndCreative() {
        add("a", BannerPriority.INTERNAL, "home_side", "a1");
        BannerClickService clicks = new BannerClickService(tokens, repository, counters, cache, () -> e -> {
        }, clock, Duration.ofSeconds(30), true, "/");
        String token = tokens.sign(new BannerTokenService.Claims("a", "a1", "home_side", "ro", "v1", "s1", "b1", "home", NOW));
        assertThat(clicks.click(token, new BannerClickService.ClickContext("UA", "10.0.0.1", null)).recorded()).isTrue();
        assertThat(repository.campaigns.get("a").getSlotClicks()).containsEntry("home_side", 1L);
        assertThat(repository.creatives.get("a1").getSlotClicks()).containsEntry("home_side", 1L);
        assertThat(repository.creatives.get("a1").getClicks()).isEqualTo(1);
    }

    @Test
    void zonesListBannersPerSlotWithCountersAndCoverage() {
        add("a", BannerPriority.INTERNAL, "home_between_1", "a1", "a2");
        BannerCampaign ru = add("ru", BannerPriority.INTERNAL, "home_between_1", "r1");
        ru.getTargeting().getLangs().add("ru");
        BannerCampaign old = add("old", BannerPriority.INTERNAL, "listing_top", "o1");
        old.setStatus(BannerStatus.ENDED);
        old.setServedImpressions(40);
        old.setClicks(2);
        BannerCampaign rejected = add("rej", BannerPriority.INTERNAL, "listing_top", "x1");
        rejected.setStatus(BannerStatus.REJECTED);
        buffer.record("a", "a1", "home_between_1");
        buffer.record("a", "a1", "home_between_1");
        buffer.record("a", "a2", "home_between_1");
        buffer.flush();
        cache.invalidate();
        BannerZoneService zones = new BannerZoneService(repository, cache, selector, null, clock);
        Map<String, ZoneDto> byId = new HashMap<>();
        for (ZoneDto z : zones.zones()) {
            byId.put(z.id(), z);
        }
        ZoneDto home = byId.get("home_between_1");
        assertThat(home.rows()).hasSize(3);
        ZoneRowDto a1 = home.rows().stream().filter(r -> r.creativeId().equals("a1")).findFirst().orElseThrow();
        assertThat(a1.impressions()).isEqualTo(2);
        assertThat(a1.live()).isTrue();
        assertThat(home.coverage()).containsEntry("ro-desktop", true).containsEntry("ru-mobile", true);
        ZoneDto listing = byId.get("listing_top");
        assertThat(listing.rows()).extracting(ZoneRowDto::campaignId).containsExactly("old");
        assertThat(listing.rows().get(0).impressions()).isEqualTo(40);
        assertThat(listing.rows().get(0).live()).isFalse();
        assertThat(listing.coverage().values()).containsOnly(false);
        assertThat(byId.get("search_top").rows()).isEmpty();
        assertThat(byId.get("search_top").coverage().values()).containsOnly(false);
    }

    @Test
    void coverageIsPerLanguage() {
        BannerCampaign ro = add("ro", BannerPriority.FALLBACK, "search_top", "s1");
        ro.getTargeting().getLangs().add("ro");
        cache.invalidate();
        BannerZoneService zones = new BannerZoneService(repository, cache, selector, null, clock);
        ZoneDto search = zones.zones().stream().filter(z -> z.id().equals("search_top")).findFirst().orElseThrow();
        assertThat(search.coverage()).containsEntry("ro-desktop", true).containsEntry("ro-mobile", true)
                .containsEntry("ru-desktop", false).containsEntry("ru-mobile", false);
        List<ZoneDto> all = zones.zones();
        assertThat(all).hasSize(16);
    }
}
