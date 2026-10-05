package com.naqqa.analytics.banners;

import com.naqqa.analytics.banners.engine.BannerRequest;
import com.naqqa.analytics.banners.engine.BannerSlots;
import com.naqqa.analytics.banners.model.BannerSlotSettings;
import com.naqqa.analytics.banners.service.BannerException;
import com.naqqa.analytics.banners.service.BannerSlotRegistry;
import com.naqqa.analytics.banners.store.BannerSlotRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BannerSlotRegistryTest {

    static class MemoryRepository extends BannerSlotRepository {
        final Map<String, BannerSlotSettings> docs = new LinkedHashMap<>();

        MemoryRepository() {
            super(null);
        }

        @Override
        public List<BannerSlotSettings> findAll() {
            return new ArrayList<>(docs.values());
        }

        @Override
        public BannerSlotSettings find(String id) {
            return docs.get(id);
        }

        @Override
        public boolean exists(String id) {
            return docs.containsKey(id);
        }

        @Override
        public BannerSlotSettings insert(BannerSlotSettings settings) {
            docs.put(settings.getId(), settings);
            return settings;
        }

        @Override
        public BannerSlotSettings save(BannerSlotSettings settings) {
            docs.put(settings.getId(), settings);
            return settings;
        }
    }

    private final MemoryRepository repository = new MemoryRepository();
    private final BannerSlotRegistry registry = new BannerSlotRegistry(repository, Clock.fixed(Instant.parse("2026-10-05T10:00:00Z"), ZoneOffset.UTC), 30_000);

    private static BannerRequest request(String slot, String pageType, String device) {
        return BannerRequest.builder().slot(slot).pageType(pageType).device(device).build();
    }

    @Test
    void seedsEveryCatalogueSlotWithDefaults() {
        registry.seed();
        assertThat(repository.docs).hasSize(BannerSlots.ALL.size());
        assertThat(registry.settings("chat_card").isEnabled()).isFalse();
        assertThat(registry.settings("app_promo_strip").isEnabled()).isFalse();
        assertThat(registry.settings("home_hero").isEnabled()).isTrue();
        assertThat(registry.settings("listing_sidebar").isMobileEnabled()).isFalse();
        assertThat(registry.settings("booklet_interstitial").getParams()).containsEntry("afterPage", 4).containsEntry("every", 8);
        registry.seed();
        assertThat(repository.docs).hasSize(BannerSlots.ALL.size());
    }

    @Test
    void usesDefaultsWhenNothingIsStored() {
        assertThat(registry.servable(request("home_hero", "home", "desktop"))).isTrue();
        assertThat(registry.servable(request("chat_card", "chat", "desktop"))).isFalse();
        assertThat(registry.slot("detail_side").desktop()).isEqualTo(new BannerSlots.Size(300, 250));
    }

    @Test
    void appliesUpdatesToDeliveryAndValidation() {
        BannerSlotSettings s = registry.settings("listing_top");
        s.setPageTypes(List.of("promotions"));
        s.setDesktop(new BannerSlotSettings.Size(1000, 100));
        s.setMobileEnabled(false);
        registry.update("listing_top", s, "admin");

        assertThat(registry.servable(request("listing_top", "promotions", "desktop"))).isTrue();
        assertThat(registry.servable(request("listing_top", "products", "desktop"))).isFalse();
        assertThat(registry.servable(request("listing_top", "promotions", "mobile"))).isFalse();
        assertThat(registry.slot("listing_top").desktop()).isEqualTo(new BannerSlots.Size(1000, 100));
        assertThat(repository.docs.get("listing_top").getUpdatedBy()).isEqualTo("admin");

        BannerSlotSettings off = registry.settings("home_hero");
        off.setEnabled(false);
        registry.update("home_hero", off, "admin");
        assertThat(registry.servable(request("home_hero", "home", "desktop"))).isFalse();

        registry.reset("home_hero", "admin");
        assertThat(registry.servable(request("home_hero", "home", "desktop"))).isTrue();
    }

    @Test
    void rejectsInvalidSettings() {
        BannerSlotSettings s = registry.settings("booklet_interstitial");
        s.setParams(new LinkedHashMap<>(Map.of("every", 1)));
        assertThatThrownBy(() -> registry.update("booklet_interstitial", s, "admin")).isInstanceOf(BannerException.class);

        BannerSlotSettings size = registry.settings("home_side");
        size.setDesktop(new BannerSlotSettings.Size(0, 100));
        assertThatThrownBy(() -> registry.update("home_side", size, "admin")).isInstanceOf(BannerException.class);

        BannerSlotSettings pages = registry.settings("home_side");
        pages.setPageTypes(List.of("promotions"));
        assertThatThrownBy(() -> registry.update("home_side", pages, "admin")).isInstanceOf(BannerException.class);

        assertThatThrownBy(() -> registry.update("nope", new BannerSlotSettings(), "admin")).isInstanceOf(BannerException.class);
    }
}
