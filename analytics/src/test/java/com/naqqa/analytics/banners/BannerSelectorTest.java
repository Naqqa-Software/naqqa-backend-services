package com.naqqa.analytics.banners;

import com.naqqa.analytics.banners.engine.BannerPacingCalculator;
import com.naqqa.analytics.banners.engine.BannerRequest;
import com.naqqa.analytics.banners.engine.BannerSelector;
import com.naqqa.analytics.banners.engine.BannerSelector.Candidate;
import com.naqqa.analytics.banners.engine.BannerSelector.Rejection;
import com.naqqa.analytics.banners.engine.BannerSelector.Selection;
import com.naqqa.analytics.banners.engine.BannerTargetingEngine;
import com.naqqa.analytics.banners.model.BannerCampaign;
import com.naqqa.analytics.banners.model.BannerCreative;
import com.naqqa.analytics.banners.model.BannerPriority;
import com.naqqa.analytics.banners.model.BannerStatus;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

import static com.naqqa.analytics.banners.BannerFixtures.campaign;
import static com.naqqa.analytics.banners.BannerFixtures.candidate;
import static com.naqqa.analytics.banners.BannerFixtures.creative;
import static com.naqqa.analytics.banners.BannerFixtures.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class BannerSelectorTest {

    private final BannerSelector selector = new BannerSelector(new BannerTargetingEngine(ZoneId.of("Europe/Chisinau")),
            new BannerPacingCalculator(), true);

    private static RandomGenerator random() {
        return new SplittableRandom(42);
    }

    @Test
    void paidBeatsInternalBeatsFallback() {
        Candidate paid = candidate(campaign("paid", BannerPriority.PAID, "home_between_1"));
        Candidate internal = candidate(campaign("internal", BannerPriority.INTERNAL, "home_between_1"));
        Candidate fallback = candidate(campaign("fallback", BannerPriority.FALLBACK, "home_between_1"));
        BannerRequest r = request("home_between_1").build();
        RandomGenerator rnd = random();
        for (int i = 0; i < 200; i++) {
            assertThat(selector.select(List.of(fallback, internal, paid), r, id -> 0, rnd).campaign().getId()).isEqualTo("paid");
        }
        assertThat(selector.select(List.of(fallback, internal), r, id -> 0, rnd).campaign().getId()).isEqualTo("internal");
        assertThat(selector.select(List.of(fallback), r, id -> 0, rnd).campaign().getId()).isEqualTo("fallback");
    }

    @Test
    void ineligiblePaidFallsThroughToLowerTier() {
        BannerCampaign paid = campaign("paid", BannerPriority.PAID, "listing_top");
        Candidate internal = candidate(campaign("internal", BannerPriority.INTERNAL, "home_between_1"));
        Selection s = selector.select(List.of(candidate(paid), internal), request("home_between_1").build(), id -> 0, random());
        assertThat(s.campaign().getId()).isEqualTo("internal");
    }

    @Test
    void weightedRotationWithinTier() {
        BannerCampaign a = campaign("a", BannerPriority.PAID, "home_side");
        a.setWeight(3);
        BannerCampaign b = campaign("b", BannerPriority.PAID, "home_side");
        b.setWeight(1);
        Map<String, Integer> counts = new HashMap<>();
        RandomGenerator rnd = random();
        int n = 40_000;
        for (int i = 0; i < n; i++) {
            Selection s = selector.select(List.of(candidate(a), candidate(b)), request("home_side").build(), id -> 0, rnd);
            counts.merge(s.campaign().getId(), 1, Integer::sum);
        }
        assertThat(counts.get("a") / (double) n).isCloseTo(0.75, within(0.01));
        assertThat(counts.get("b") / (double) n).isCloseTo(0.25, within(0.01));
    }

    @Test
    void abSplitIsStickyPerVisitorAndFollowsWeights() {
        BannerCampaign c = campaign("ab", BannerPriority.PAID, "home_side");
        c.setAbTest(true);
        BannerCreative x = creative("x", "ab", 1);
        BannerCreative y = creative("y", "ab", 1);
        Candidate cand = new Candidate(c, List.of(x, y));
        Map<String, Integer> counts = new HashMap<>();
        int n = 20_000;
        RandomGenerator rnd = random();
        for (int i = 0; i < n; i++) {
            String vid = "visitor-" + i;
            Selection first = selector.select(List.of(cand), request("home_side").vid(vid).build(), id -> 0, rnd);
            Selection again = selector.select(List.of(cand), request("home_side").vid(vid).build(), id -> 0, rnd);
            assertThat(again.creative().getId()).isEqualTo(first.creative().getId());
            counts.merge(first.creative().getId(), 1, Integer::sum);
        }
        assertThat(counts.get("x") / (double) n).isCloseTo(0.5, within(0.02));

        y.setWeight(3);
        counts.clear();
        for (int i = 0; i < n; i++) {
            counts.merge(selector.select(List.of(cand), request("home_side").vid("v-" + i).build(), id -> 0, rnd).creative().getId(), 1, Integer::sum);
        }
        assertThat(counts.get("y") / (double) n).isCloseTo(0.75, within(0.02));
    }

    @Test
    void withoutAbTestCreativesRotateByWeight() {
        BannerCampaign c = campaign("rot", BannerPriority.PAID, "home_side");
        Candidate cand = new Candidate(c, List.of(creative("p", "rot", 1), creative("q", "rot", 4)));
        Map<String, Integer> counts = new HashMap<>();
        RandomGenerator rnd = random();
        for (int i = 0; i < 10_000; i++) {
            counts.merge(selector.select(List.of(cand), request("home_side").build(), id -> 0, rnd).creative().getId(), 1, Integer::sum);
        }
        assertThat(counts.get("q") / 10_000.0).isCloseTo(0.8, within(0.02));
    }

    @Test
    void frequencyCapPerVisitor() {
        BannerCampaign capped = campaign("capped", BannerPriority.PAID, "home_side");
        capped.setFrequencyCapPerDay(3);
        Candidate fallback = candidate(campaign("house", BannerPriority.FALLBACK, "home_side"));
        Map<String, Integer> served = new HashMap<>();
        RandomGenerator rnd = random();
        List<String> seen = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Selection s = selector.select(List.of(candidate(capped), fallback), request("home_side").vid("v").build(),
                    id -> served.getOrDefault(id, 0), rnd);
            served.merge(s.campaign().getId(), 1, Integer::sum);
            seen.add(s.campaign().getId());
        }
        assertThat(seen).containsExactly("capped", "capped", "capped", "house", "house");
        assertThat(selector.reject(candidate(capped), request("home_side").vid("v").build(), id -> 3, false)).isEqualTo(Rejection.FREQUENCY);
        assertThat(selector.reject(candidate(capped), request("home_side").vid("other").build(), id -> 0, false)).isNull();
    }

    @Test
    void statusScheduleAndBudget() {
        BannerCampaign paused = campaign("p", BannerPriority.PAID, "home_side");
        paused.setStatus(BannerStatus.PAUSED);
        assertThat(selector.reject(candidate(paused), request("home_side").build(), id -> 0, false)).isEqualTo(Rejection.STATUS);

        BannerCampaign future = campaign("f", BannerPriority.PAID, "home_side");
        future.setStart(BannerFixtures.NOW.plusSeconds(60));
        assertThat(selector.reject(candidate(future), request("home_side").build(), id -> 0, false)).isEqualTo(Rejection.SCHEDULE);

        BannerCampaign spent = campaign("s", BannerPriority.PAID, "home_side");
        spent.setBudgetImpressions(100L);
        spent.setServedImpressions(100);
        assertThat(selector.reject(candidate(spent), request("home_side").build(), id -> 0, false)).isEqualTo(Rejection.BUDGET);

        BannerCampaign clicks = campaign("k", BannerPriority.PAID, "home_side");
        clicks.setBudgetClicks(10L);
        clicks.setClicks(10);
        assertThat(selector.reject(candidate(clicks), request("home_side").build(), id -> 0, false)).isEqualTo(Rejection.BUDGET);
    }

    @Test
    void competitorsExcludedOnCompanyPage() {
        BannerCampaign own = campaign("own", BannerPriority.PAID, "detail_side");
        own.setCompanyId("8");
        BannerCampaign rival = campaign("rival", BannerPriority.PAID, "detail_side");
        rival.setCompanyId("9");
        BannerCampaign house = campaign("house", BannerPriority.INTERNAL, "detail_side");
        BannerRequest companyPage = request("detail_side").pageType("company").companyId("8").build();
        List<Candidate> eligible = selector.eligible(List.of(candidate(own), candidate(rival), candidate(house)), companyPage, id -> 0);
        assertThat(eligible).extracting(c -> c.campaign().getId()).containsExactly("own", "house");

        BannerRequest otherPage = request("detail_side").pageType("promotion").companyId("8").build();
        assertThat(selector.eligible(List.of(candidate(own), candidate(rival)), otherPage, id -> 0)).hasSize(2);
    }

    @Test
    void competitorRuleCanBeEnabledPerCampaignWhenGloballyOff() {
        BannerSelector relaxed = new BannerSelector(new BannerTargetingEngine(ZoneId.of("Europe/Chisinau")), new BannerPacingCalculator(), false);
        BannerCampaign own = campaign("own", BannerPriority.PAID, "detail_side");
        own.setCompanyId("8");
        BannerCampaign rival = campaign("rival", BannerPriority.PAID, "detail_side");
        rival.setCompanyId("9");
        BannerRequest companyPage = request("detail_side").pageType("company").companyId("8").build();
        assertThat(relaxed.eligible(List.of(candidate(own), candidate(rival)), companyPage, id -> 0)).hasSize(2);
        own.setExcludeCompetitorsOnCompanyPage(true);
        assertThat(relaxed.eligible(List.of(candidate(own), candidate(rival)), companyPage, id -> 0))
                .extracting(c -> c.campaign().getId()).containsExactly("own");
    }

    @Test
    void companyTopIsReservedForThatCompany() {
        BannerCampaign own = campaign("own", BannerPriority.PAID, "company_top");
        own.setCompanyId("8");
        BannerCampaign house = campaign("house", BannerPriority.INTERNAL, "company_top");
        BannerRequest r = request("company_top").pageType("company").companyId("8").build();
        assertThat(selector.eligible(List.of(candidate(own), candidate(house)), r, id -> 0))
                .extracting(c -> c.campaign().getId()).containsExactly("own");
        BannerRequest other = request("company_top").pageType("company").companyId("9").build();
        assertThat(selector.eligible(List.of(candidate(own), candidate(house)), other, id -> 0)).isEmpty();
    }

    @Test
    void creativesRestrictedBySlotAndActiveFlag() {
        BannerCampaign c = campaign("c", BannerPriority.PAID, "home_side", "listing_top");
        BannerCreative side = creative("side", "c", 1);
        side.setSlots(List.of("home_side"));
        BannerCreative inactive = creative("off", "c", 1);
        inactive.setActive(false);
        Candidate cand = new Candidate(c, List.of(side, inactive));
        assertThat(selector.select(List.of(cand), request("home_side").build(), id -> 0, random()).creative().getId()).isEqualTo("side");
        assertThat(selector.reject(cand, request("listing_top").build(), id -> 0, false)).isEqualTo(Rejection.NO_CREATIVE);
    }

    @Test
    void nothingEligibleReturnsNull() {
        assertThat(selector.select(List.of(), request("home_side").build(), id -> 0, random())).isNull();
    }
}
