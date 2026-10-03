package com.naqqa.analytics.banners.engine;

import com.naqqa.analytics.banners.model.BannerCampaign;
import com.naqqa.analytics.banners.model.BannerPacing;

import java.time.Duration;
import java.time.Instant;

public final class BannerPacingCalculator {

    public enum State {
        UNLIMITED,
        NOT_STARTED,
        AHEAD,
        ON_TRACK,
        BEHIND,
        DONE
    }

    public record Pacing(State state, Long budget, long delivered, long expected, double deliveredPct, double elapsedPct,
                         Instant projectedEnd) {
    }

    private final double tolerance;
    private final long minSlack;

    public BannerPacingCalculator(double tolerance, long minSlack) {
        this.tolerance = Math.max(0, tolerance);
        this.minSlack = Math.max(0, minSlack);
    }

    public BannerPacingCalculator() {
        this(0.02, 20);
    }

    public static boolean exhausted(BannerCampaign c) {
        if (c.getBudgetImpressions() != null && c.getBudgetImpressions() > 0 && c.getServedImpressions() >= c.getBudgetImpressions()) {
            return true;
        }
        return c.getBudgetClicks() != null && c.getBudgetClicks() > 0 && c.getClicks() >= c.getBudgetClicks();
    }

    public long expected(BannerCampaign c, Instant now) {
        Long budget = c.getBudgetImpressions();
        if (budget == null || budget <= 0 || c.getStart() == null || c.getEnd() == null) {
            return budget == null ? 0 : budget;
        }
        long total = Duration.between(c.getStart(), c.getEnd()).toMillis();
        if (total <= 0) {
            return budget;
        }
        long elapsed = Math.max(0, Math.min(total, Duration.between(c.getStart(), now).toMillis()));
        return (long) Math.floor((double) budget * elapsed / total);
    }

    public boolean allows(BannerCampaign c, Instant now) {
        if (exhausted(c)) {
            return false;
        }
        if (c.getPacing() == BannerPacing.ASAP) {
            return true;
        }
        Long budget = c.getBudgetImpressions();
        if (budget == null || budget <= 0 || c.getStart() == null || c.getEnd() == null) {
            return true;
        }
        long slack = Math.max(minSlack, (long) Math.ceil(budget * tolerance));
        return c.getServedImpressions() < expected(c, now) + slack;
    }

    public Pacing pacing(BannerCampaign c, Instant now) {
        Long budget = c.getBudgetImpressions();
        long delivered = c.getServedImpressions();
        if (budget == null || budget <= 0) {
            return new Pacing(State.UNLIMITED, null, delivered, 0, 0, elapsedPct(c, now), null);
        }
        double deliveredPct = Math.min(1.0, (double) delivered / budget);
        double elapsed = elapsedPct(c, now);
        if (delivered >= budget) {
            return new Pacing(State.DONE, budget, delivered, budget, deliveredPct, elapsed, null);
        }
        if (c.getStart() != null && now.isBefore(c.getStart())) {
            return new Pacing(State.NOT_STARTED, budget, delivered, 0, deliveredPct, 0, null);
        }
        long expected = c.getStart() != null && c.getEnd() != null ? expected(c, now) : delivered;
        Instant projected = projectedEnd(c, now);
        State state;
        if (c.getStart() == null || c.getEnd() == null) {
            state = State.ON_TRACK;
        } else {
            long slack = Math.max(minSlack, (long) Math.ceil(budget * 0.05));
            if (delivered > expected + slack) {
                state = State.AHEAD;
            } else if (delivered + slack < expected) {
                state = State.BEHIND;
            } else {
                state = State.ON_TRACK;
            }
        }
        return new Pacing(state, budget, delivered, expected, deliveredPct, elapsed, projected);
    }

    public Instant projectedEnd(BannerCampaign c, Instant now) {
        Long budget = c.getBudgetImpressions();
        if (budget == null || budget <= 0 || c.getStart() == null || c.getServedImpressions() <= 0) {
            return null;
        }
        long elapsed = Duration.between(c.getStart(), now).toMillis();
        if (elapsed <= 0) {
            return null;
        }
        double rate = (double) c.getServedImpressions() / elapsed;
        long remaining = Math.max(0, budget - c.getServedImpressions());
        return now.plusMillis((long) Math.ceil(remaining / rate));
    }

    private static double elapsedPct(BannerCampaign c, Instant now) {
        if (c.getStart() == null || c.getEnd() == null) {
            return 0;
        }
        long total = Duration.between(c.getStart(), c.getEnd()).toMillis();
        if (total <= 0) {
            return 1;
        }
        long elapsed = Math.max(0, Math.min(total, Duration.between(c.getStart(), now).toMillis()));
        return (double) elapsed / total;
    }
}
