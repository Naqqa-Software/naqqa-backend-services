package com.naqqa.analytics.banners;

import com.naqqa.analytics.banners.engine.BannerPacingCalculator;
import com.naqqa.analytics.banners.engine.BannerPacingCalculator.State;
import com.naqqa.analytics.banners.model.BannerCampaign;
import com.naqqa.analytics.banners.model.BannerPacing;
import com.naqqa.analytics.banners.model.BannerPriority;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static com.naqqa.analytics.banners.BannerFixtures.campaign;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class BannerPacingTest {

    private final BannerPacingCalculator pacing = new BannerPacingCalculator(0.02, 20);
    private final Instant start = Instant.parse("2026-10-01T00:00:00Z");
    private final Instant end = Instant.parse("2026-10-11T00:00:00Z");

    private BannerCampaign budgeted(long budget, long served) {
        BannerCampaign c = campaign("p", BannerPriority.PAID, "home_side");
        c.setStart(start);
        c.setEnd(end);
        c.setBudgetImpressions(budget);
        c.setServedImpressions(served);
        return c;
    }

    @Test
    void expectedIsLinearOverFlight() {
        BannerCampaign c = budgeted(10_000, 0);
        assertThat(pacing.expected(c, start)).isZero();
        assertThat(pacing.expected(c, Instant.parse("2026-10-06T00:00:00Z"))).isEqualTo(5_000);
        assertThat(pacing.expected(c, end)).isEqualTo(10_000);
        assertThat(pacing.expected(c, end.plusSeconds(3600))).isEqualTo(10_000);
    }

    @Test
    void evenPacingThrottlesWhenAhead() {
        Instant mid = Instant.parse("2026-10-06T00:00:00Z");
        assertThat(pacing.allows(budgeted(10_000, 5_000), mid)).isTrue();
        assertThat(pacing.allows(budgeted(10_000, 5_199), mid)).isTrue();
        assertThat(pacing.allows(budgeted(10_000, 5_200), mid)).isFalse();
        assertThat(pacing.allows(budgeted(10_000, 2_000), mid)).isTrue();
    }

    @Test
    void minimumSlackAllowsSmallBudgetsToStart() {
        BannerCampaign c = budgeted(100, 19);
        assertThat(pacing.allows(c, start.plusSeconds(1))).isTrue();
        c.setServedImpressions(20);
        assertThat(pacing.allows(c, start.plusSeconds(1))).isFalse();
    }

    @Test
    void asapIgnoresPacingButNotBudget() {
        BannerCampaign c = budgeted(10_000, 9_999);
        c.setPacing(BannerPacing.ASAP);
        assertThat(pacing.allows(c, start.plusSeconds(10))).isTrue();
        c.setServedImpressions(10_000);
        assertThat(pacing.allows(c, start.plusSeconds(10))).isFalse();
    }

    @Test
    void unlimitedCampaignsAlwaysAllowed() {
        BannerCampaign c = campaign("u", BannerPriority.INTERNAL, "home_side");
        c.setServedImpressions(1_000_000);
        assertThat(pacing.allows(c, Instant.now())).isTrue();
        assertThat(pacing.pacing(c, Instant.now()).state()).isEqualTo(State.UNLIMITED);
    }

    @Test
    void statesAndForecast() {
        Instant mid = Instant.parse("2026-10-06T00:00:00Z");
        assertThat(pacing.pacing(budgeted(10_000, 5_000), mid).state()).isEqualTo(State.ON_TRACK);
        assertThat(pacing.pacing(budgeted(10_000, 7_000), mid).state()).isEqualTo(State.AHEAD);
        assertThat(pacing.pacing(budgeted(10_000, 2_000), mid).state()).isEqualTo(State.BEHIND);
        assertThat(pacing.pacing(budgeted(10_000, 10_000), mid).state()).isEqualTo(State.DONE);
        assertThat(pacing.pacing(budgeted(10_000, 0), start.minusSeconds(5)).state()).isEqualTo(State.NOT_STARTED);
        assertThat(pacing.projectedEnd(budgeted(10_000, 2_500), mid)).isCloseTo(Instant.parse("2026-10-21T00:00:00Z"), within(1, ChronoUnit.SECONDS));
        assertThat(pacing.pacing(budgeted(10_000, 5_000), mid).elapsedPct()).isEqualTo(0.5);
    }
}
