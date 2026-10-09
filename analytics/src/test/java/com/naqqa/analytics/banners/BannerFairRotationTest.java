package com.naqqa.analytics.banners;

import com.naqqa.analytics.banners.engine.BannerPacingCalculator;
import com.naqqa.analytics.banners.engine.BannerSelector;
import com.naqqa.analytics.banners.engine.BannerSelector.Candidate;
import com.naqqa.analytics.banners.engine.BannerSelector.Fairness;
import com.naqqa.analytics.banners.engine.BannerSelector.Selection;
import com.naqqa.analytics.banners.engine.BannerTargetingEngine;
import com.naqqa.analytics.banners.model.BannerCampaign;
import com.naqqa.analytics.banners.model.BannerCreative;
import com.naqqa.analytics.banners.model.BannerPriority;
import com.naqqa.analytics.banners.store.MemoryBannerCounters;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

import static com.naqqa.analytics.banners.BannerFixtures.NOW;
import static com.naqqa.analytics.banners.BannerFixtures.campaign;
import static com.naqqa.analytics.banners.BannerFixtures.candidate;
import static com.naqqa.analytics.banners.BannerFixtures.creative;
import static com.naqqa.analytics.banners.BannerFixtures.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class BannerFairRotationTest {

    private static final String SLOT = "home_between_1";
    private static final LocalDate DAY = LocalDate.of(2026, 10, 7);

    private final BannerSelector selector = new BannerSelector(new BannerTargetingEngine(ZoneId.of("Europe/Chisinau")),
            new BannerPacingCalculator(), true, Fairness.DEFAULT);

    private final MemoryBannerCounters counters = new MemoryBannerCounters(Clock.fixed(NOW, ZoneOffset.UTC), 500_000);

    private Selection serve(List<Candidate> candidates, String vid, RandomGenerator random) {
        Map<String, Long> rotation = counters.rotation(SLOT, DAY);
        Selection s = selector.select(candidates, request(SLOT).vid(vid).build(), id -> counters.servedToday(vid, id, DAY),
                key -> rotation.getOrDefault(key, 0L), random);
        if (s != null) {
            counters.recordServe(vid, s.campaign().getId(), DAY);
            counters.recordRotation(SLOT, DAY, Map.of(s.campaign().getId(), s.campaignStep(),
                    BannerSelector.creativeKey(s.creative().getId()), s.creativeStep()));
        }
        return s;
    }

    private static List<Candidate> banners(int n) {
        List<Candidate> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(candidate(campaign("b" + i, BannerPriority.INTERNAL, SLOT)));
        }
        return out;
    }

    @Test
    void tenThousandRequestsSpreadEvenlyAcrossFourBanners() {
        List<Candidate> candidates = banners(4);
        Map<String, Integer> counts = new HashMap<>();
        RandomGenerator rnd = new SplittableRandom(7);
        for (int i = 0; i < 10_000; i++) {
            Selection s = serve(candidates, "visitor-" + (i % 700), rnd);
            counts.merge(s.campaign().getId(), 1, Integer::sum);
        }
        int max = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        int min = counts.values().stream().mapToInt(Integer::intValue).min().orElse(0);
        assertThat(counts).hasSize(4);
        assertThat(max - min).isLessThanOrEqualTo(3);
    }

    @Test
    void fiveBannersWithAnonymousVisitorsStayBalanced() {
        List<Candidate> candidates = banners(5);
        Map<String, Integer> counts = new HashMap<>();
        RandomGenerator rnd = new SplittableRandom(11);
        for (int i = 0; i < 10_000; i++) {
            counts.merge(serve(candidates, null, rnd).campaign().getId(), 1, Integer::sum);
        }
        for (int c : counts.values()) {
            assertThat(c).isBetween(1997, 2003);
        }
    }

    @Test
    void weightsAreHonouredExactly() {
        BannerCampaign a = campaign("a", BannerPriority.PAID, SLOT);
        a.setWeight(3);
        BannerCampaign b = campaign("b", BannerPriority.PAID, SLOT);
        List<Candidate> candidates = List.of(candidate(a), candidate(b));
        Map<String, Integer> counts = new HashMap<>();
        RandomGenerator rnd = new SplittableRandom(3);
        for (int i = 0; i < 8_000; i++) {
            counts.merge(serve(candidates, "v" + (i % 50), rnd).campaign().getId(), 1, Integer::sum);
        }
        assertThat(counts.get("a") / 8_000.0).isCloseTo(0.75, within(0.002));
    }

    @Test
    void expiredBannersAreNeverChosen() {
        BannerCampaign expired = campaign("expired", BannerPriority.PAID, SLOT);
        expired.setEnd(NOW.minusSeconds(1));
        BannerCampaign endsNow = campaign("ends-now", BannerPriority.PAID, SLOT);
        endsNow.setEnd(NOW);
        List<Candidate> candidates = new ArrayList<>(banners(2));
        candidates.add(candidate(expired));
        candidates.add(candidate(endsNow));
        RandomGenerator rnd = new SplittableRandom(5);
        for (int i = 0; i < 2_000; i++) {
            String id = serve(candidates, "v" + i, rnd).campaign().getId();
            assertThat(id).isNotIn("expired", "ends-now");
        }
    }

    @Test
    void futureBannersAreNeverChosen() {
        BannerCampaign future = campaign("future", BannerPriority.PAID, SLOT);
        future.setStart(NOW.plus(Duration.ofMinutes(1)));
        List<Candidate> candidates = new ArrayList<>(banners(2));
        candidates.add(candidate(future));
        RandomGenerator rnd = new SplittableRandom(5);
        for (int i = 0; i < 2_000; i++) {
            assertThat(serve(candidates, "v" + i, rnd).campaign().getId()).isNotEqualTo("future");
        }
        assertThat(serve(List.of(candidate(future)), "v", rnd)).isNull();
    }

    @Test
    void sameVisitorSeesEveryBannerBeforeARepeat() {
        List<Candidate> candidates = banners(4);
        RandomGenerator rnd = new SplittableRandom(13);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 4; i++) {
            seen.add(serve(candidates, "same-visitor", rnd).campaign().getId());
        }
        assertThat(seen).hasSize(4);
    }

    @Test
    void differentVisitorsGetDifferentBanners() {
        List<Candidate> candidates = banners(4);
        RandomGenerator rnd = new SplittableRandom(17);
        Set<String> firstViews = new HashSet<>();
        for (int i = 0; i < 40; i++) {
            firstViews.add(serve(candidates, "new-visitor-" + i, rnd).campaign().getId());
        }
        assertThat(firstViews).hasSize(4);
    }

    @Test
    void tiesAreBrokenRandomly() {
        List<Candidate> candidates = banners(4);
        Map<String, Integer> counts = new HashMap<>();
        RandomGenerator rnd = new SplittableRandom(19);
        int n = 20_000;
        for (int i = 0; i < n; i++) {
            Selection s = selector.select(candidates, request(SLOT).vid(null).build(), id -> 0, key -> 0L, rnd);
            counts.merge(s.campaign().getId(), 1, Integer::sum);
        }
        for (int c : counts.values()) {
            assertThat(c / (double) n).isCloseTo(0.25, within(0.02));
        }
    }

    @Test
    void leastServedBannerIsPreferred() {
        List<Candidate> candidates = banners(3);
        Map<String, Long> rotation = Map.of("b0", 50L, "b1", 50L, "b2", 10L);
        RandomGenerator rnd = new SplittableRandom(23);
        for (int i = 0; i < 100; i++) {
            Selection s = selector.select(candidates, request(SLOT).build(), id -> 0, key -> rotation.getOrDefault(key, 0L), rnd);
            assertThat(s.campaign().getId()).isEqualTo("b2");
        }
    }

    @Test
    void newBannerCatchUpIsBounded() {
        List<Candidate> candidates = banners(2);
        for (int i = 0; i < 5_000; i++) {
            counters.recordRotation(SLOT, DAY, "b0");
            counters.recordRotation(SLOT, DAY, "b1");
        }
        List<Candidate> withNew = new ArrayList<>(candidates);
        withNew.add(candidate(campaign("fresh", BannerPriority.INTERNAL, SLOT)));
        RandomGenerator rnd = new SplittableRandom(29);
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 400; i++) {
            counts.merge(serve(withNew, "v" + i, rnd).campaign().getId(), 1, Integer::sum);
        }
        assertThat(counts.get("fresh")).isBetween(99, 210);
        assertThat(counts.getOrDefault("b0", 0) + counts.getOrDefault("b1", 0)).isGreaterThan(180);
        Map<String, Long> rotation = counters.rotation(SLOT, DAY);
        assertThat(Math.abs(rotation.get("fresh") - rotation.get("b0"))).isLessThanOrEqualTo(2);
    }

    @Test
    void paidPriorityIsKeptAboveRotation() {
        Candidate paid = candidate(campaign("paid", BannerPriority.PAID, SLOT));
        Candidate house = candidate(campaign("house", BannerPriority.FALLBACK, SLOT));
        RandomGenerator rnd = new SplittableRandom(31);
        for (int i = 0; i < 500; i++) {
            assertThat(serve(List.of(house, paid), "v" + i, rnd).campaign().getId()).isEqualTo("paid");
        }
        assertThat(serve(List.of(house), "v", rnd).campaign().getId()).isEqualTo("house");
    }

    @Test
    void creativesOfACampaignRotateEvenly() {
        BannerCampaign c = campaign("multi", BannerPriority.INTERNAL, SLOT);
        BannerCreative x = creative("x", "multi", 1);
        BannerCreative y = creative("y", "multi", 1);
        BannerCreative z = creative("z", "multi", 1);
        List<Candidate> candidates = List.of(new Candidate(c, List.of(x, y, z)));
        Map<String, Integer> counts = new HashMap<>();
        RandomGenerator rnd = new SplittableRandom(37);
        for (int i = 0; i < 3_000; i++) {
            counts.merge(serve(candidates, "v" + i, rnd).creative().getId(), 1, Integer::sum);
        }
        int max = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        int min = counts.values().stream().mapToInt(Integer::intValue).min().orElse(0);
        assertThat(max - min).isLessThanOrEqualTo(2);
    }

    @Test
    void withoutRotationTheLegacyWeightedPickIsUsed() {
        List<Candidate> candidates = banners(2);
        RandomGenerator rnd = new SplittableRandom(41);
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 1_000; i++) {
            counts.merge(selector.select(candidates, request(SLOT).build(), id -> 0, rnd).campaign().getId(), 1, Integer::sum);
        }
        assertThat(counts).hasSize(2);
    }

    @Test
    void fairPickHandlesEmptyAndSingleLists() {
        RandomGenerator rnd = new SplittableRandom(1);
        assertThat(BannerSelector.fair(List.<String>of(), s -> 1, s -> 0L, null, Fairness.DEFAULT, rnd)).isNull();
        assertThat(BannerSelector.fair(List.of("only"), s -> 1, s -> 99L, null, Fairness.DEFAULT, rnd)).isEqualTo("only");
    }
}
