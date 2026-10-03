package com.naqqa.analytics.banners;

import com.naqqa.analytics.banners.engine.BannerRequest;
import com.naqqa.analytics.banners.engine.BannerTargetingEngine;
import com.naqqa.analytics.banners.model.BannerCampaign;
import com.naqqa.analytics.banners.model.BannerPriority;
import com.naqqa.analytics.banners.model.BannerTargeting;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static com.naqqa.analytics.banners.BannerFixtures.campaign;
import static com.naqqa.analytics.banners.BannerFixtures.request;
import static org.assertj.core.api.Assertions.assertThat;

class BannerTargetingEngineTest {

    private final BannerTargetingEngine engine = new BannerTargetingEngine(ZoneId.of("Europe/Chisinau"));

    @Test
    void emptyTargetingMatchesEverything() {
        BannerCampaign c = campaign("a", BannerPriority.PAID);
        assertThat(engine.matches(c, request("home_between_1").build())).isTrue();
        assertThat(engine.matches(c, BannerRequest.builder().slot("search_top").build())).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "slots,home_between_1,home_between_1,true",
            "slots,home_between_1,listing_top,false",
            "pageTypes,promotion,promotion,true",
            "pageTypes,promotion,product,false",
            "pageTypes,promotion,,false",
            "categoryIds,4,4,true",
            "categoryIds,4,5,false",
            "companyIds,8,8,true",
            "companyIds,8,9,false",
            "langs,ru,ru,true",
            "langs,ru,ro,false",
            "langs,RU,ru,true",
            "devices,mobile,mobile,true",
            "devices,mobile,phone,true",
            "devices,mobile,desktop,false",
            "cities,Chișinău,chisinau,true",
            "cities,Bălți,Chisinau,false",
            "keywords,lapte,lapte praf,true",
            "keywords,lapte,Laptele bun,true",
            "keywords,lapte,paine,false",
            "keywords,televizor,,false",
            "keywords,cafea boabe,cafea boabe lavazza,true",
    })
    void dimensionMatrix(String dimension, String allowed, String value, boolean expected) {
        BannerCampaign c = campaign("a", BannerPriority.PAID);
        BannerTargeting t = c.getTargeting();
        BannerRequest.Builder r = request("home_between_1");
        List<String> list = new java.util.ArrayList<>(List.of(allowed));
        switch (dimension) {
            case "slots" -> {
                t.setSlots(list);
                r.slot(value);
            }
            case "pageTypes" -> {
                t.setPageTypes(list);
                r.pageType(value);
            }
            case "categoryIds" -> {
                t.setCategoryIds(list);
                r.categoryId(value);
            }
            case "companyIds" -> {
                t.setCompanyIds(list);
                r.companyId(value);
            }
            case "langs" -> {
                t.setLangs(list);
                r.lang(value);
            }
            case "devices" -> {
                t.setDevices(list);
                r.device(value);
            }
            case "cities" -> {
                t.setCities(list);
                r.city(value);
            }
            case "keywords" -> {
                t.setKeywords(list);
                r.query(value);
            }
            default -> throw new IllegalArgumentException(dimension);
        }
        assertThat(engine.matches(c, r.build())).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "ANY,,true", "ANY,true,true", "ANY,false,true",
            "NEW,true,true", "NEW,false,false", "NEW,,false",
            "RETURNING,false,true", "RETURNING,true,false", "RETURNING,,false"
    })
    void visitorMatrix(BannerTargeting.Visitor visitor, Boolean newVisitor, boolean expected) {
        BannerCampaign c = campaign("a", BannerPriority.PAID);
        c.getTargeting().setVisitor(visitor);
        assertThat(engine.matches(c, request("home_side").newVisitor(newVisitor).build())).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"ANY,true,true", "ANY,false,true", "YES,true,true", "YES,false,false", "NO,false,true", "NO,true,false"})
    void loggedInMatrix(BannerTargeting.LoggedIn loggedIn, boolean value, boolean expected) {
        BannerCampaign c = campaign("a", BannerPriority.PAID);
        c.getTargeting().setLoggedIn(loggedIn);
        assertThat(engine.matches(c, request("home_side").loggedIn(value).build())).isEqualTo(expected);
    }

    @Test
    void allDimensionsCombine() {
        BannerCampaign c = campaign("a", BannerPriority.PAID, "listing_top");
        c.getTargeting().setLangs(List.of("ru"));
        c.getTargeting().setDevices(List.of("mobile"));
        c.getTargeting().setCategoryIds(List.of("4"));
        BannerRequest ok = request("listing_top").lang("ru").device("mobile").categoryId("4").build();
        assertThat(engine.matches(c, ok)).isTrue();
        assertThat(engine.matches(c, request("listing_top").lang("ru").device("desktop").categoryId("4").build())).isFalse();
        assertThat(engine.matches(c, request("listing_top").lang("ro").device("mobile").categoryId("4").build())).isFalse();
    }

    @Test
    void scheduleStartEnd() {
        BannerCampaign c = campaign("a", BannerPriority.PAID);
        c.setStart(Instant.parse("2026-10-07T00:00:00Z"));
        c.setEnd(Instant.parse("2026-10-08T00:00:00Z"));
        assertThat(engine.inSchedule(c, Instant.parse("2026-10-06T23:59:59Z"))).isFalse();
        assertThat(engine.inSchedule(c, Instant.parse("2026-10-07T00:00:00Z"))).isTrue();
        assertThat(engine.inSchedule(c, Instant.parse("2026-10-07T23:59:59Z"))).isTrue();
        assertThat(engine.inSchedule(c, Instant.parse("2026-10-08T00:00:00Z"))).isFalse();
    }

    @Test
    void scheduleDaysAndHoursUseLocalZone() {
        BannerCampaign c = campaign("a", BannerPriority.PAID);
        c.getTargeting().setDays(List.of(3));
        c.getTargeting().setHours(List.of(13));
        assertThat(engine.inSchedule(c, Instant.parse("2026-10-07T10:30:00Z"))).isTrue();
        assertThat(engine.inSchedule(c, Instant.parse("2026-10-07T13:30:00Z"))).isFalse();
        assertThat(engine.inSchedule(c, Instant.parse("2026-10-08T10:30:00Z"))).isFalse();
    }

    @Test
    void scheduleAcrossMidnightLocal() {
        BannerCampaign c = campaign("a", BannerPriority.PAID);
        c.getTargeting().setDays(List.of(4));
        assertThat(engine.inSchedule(c, Instant.parse("2026-10-07T21:30:00Z"))).isTrue();
        assertThat(engine.inSchedule(c, Instant.parse("2026-10-07T20:30:00Z"))).isFalse();
    }
}
