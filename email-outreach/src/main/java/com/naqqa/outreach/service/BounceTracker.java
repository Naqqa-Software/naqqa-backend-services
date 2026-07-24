package com.naqqa.outreach.service;

import com.naqqa.outreach.entity.OutreachAccountStateEntity;
import com.naqqa.outreach.repository.OutreachAccountStateRepository;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * Bounce protection driven by REAL data — it reads bounces straight from the {@code
 * outreach_sent_emails} log (status BOUNCED, set by the inbox sync), not a separate rolling counter.
 * Rate over the last {@value #WINDOW_DAYS} days of sends: >5% stop, >3% halve, >2% freeze; hard
 * bounces on recent sends in the last 24h: ≥2 → 24h pause, 1 → freeze. Only the pause window lives
 * in {@code outreach_account_state}.
 */
@Service
@RequiredArgsConstructor
public class BounceTracker {

    private static final int WINDOW_DAYS = 7;
    private static final int MIN_SAMPLE = 20;
    // Bounce-rate thresholds (%): shared by the live throttle and the read-only deliverability view.
    private static final double RATE_STOP = 5.0;
    private static final double RATE_REDUCE = 3.0;
    private static final double RATE_FREEZE = 2.0;

    /** action ∈ pause|stop|reduce|freeze|continue. */
    public record BounceRules(String action, String reason, double limitMultiplier) {
    }

    /**
     * Read-only snapshot for the deliverability dashboard (never mutates state). {@code recoversAt} is
     * when the throttle should clear: the exact pause end for a 24h pause, or — for a rate-driven
     * stop/reduce/freeze — the estimate at which the oldest in-window bounce ages out of the 7-day window
     * (holding sends steady). Null when {@code action} is "continue".
     */
    public record Deliverability(long sent7d, long bounced7d, double ratePct, String action, String reason,
                                 double limitMultiplier, Instant pausedUntil, Instant recoversAt) {
    }

    private final OutreachAccountStateRepository stateRepo;
    private final MongoTemplate mongo;

    public OutreachAccountStateEntity state(String profileKey) {
        return stateRepo.findByProfileKey(profileKey).orElseGet(() -> {
            OutreachAccountStateEntity s = new OutreachAccountStateEntity();
            s.setProfileKey(profileKey);
            return stateRepo.save(s);
        });
    }

    public BounceRules checkBounceRules(OutreachAccountStateEntity s) {
        Instant now = Instant.now();
        if (s.getPausedUntil() != null) {
            if (now.isBefore(s.getPausedUntil())) {
                long mins = ChronoUnit.MINUTES.between(now, s.getPausedUntil());
                return new BounceRules("pause", "Account paused for " + mins + " more min", 0.0);
            }
            s.setPausedUntil(null);
            stateRepo.save(s);
        }

        String key = s.getProfileKey();
        Instant sentFrom = now.minus(WINDOW_DAYS, ChronoUnit.DAYS);
        long sent = count(key, Criteria.where("sentAt").gte(sentFrom));
        long bounced = count(key, Criteria.where("sentAt").gte(sentFrom).and("status").is("BOUNCED"));
        double rate = sent >= MIN_SAMPLE ? (bounced / (double) sent) * 100.0 : 0.0;
        if (sent >= MIN_SAMPLE) {
            if (rate > RATE_STOP) {
                return new BounceRules("stop", String.format("Bounce rate %.1f%% > 5%% — campaign stopped", rate), 0.0);
            }
            if (rate > RATE_REDUCE) {
                return new BounceRules("reduce", String.format("Bounce rate %.1f%% > 3%% — reducing volume 50%%", rate), 0.5);
            }
            if (rate > RATE_FREEZE) {
                return new BounceRules("freeze", String.format("Bounce rate %.1f%% > 2%% — scaling frozen", rate), 1.0);
            }
        }

        // Hard bounces on RECENT sends, detected in the last 24h (old-campaign bounces don't pause us).
        long recentBounces = mongo.count(new Query(new Criteria().andOperator(
                Criteria.where("profileKey").is(key),
                Criteria.where("status").is("BOUNCED"),
                Criteria.where("sentAt").gte(sentFrom),
                Criteria.where("bouncedAt").gte(now.minus(24, ChronoUnit.HOURS)))), "outreach_sent_emails");
        if (recentBounces >= 2) {
            s.setPausedUntil(now.plus(24, ChronoUnit.HOURS));
            stateRepo.save(s);
            return new BounceRules("pause", "2 recent hard bounces — account paused 24h", 0.0);
        }
        if (recentBounces == 1) {
            return new BounceRules("freeze", "1 recent hard bounce — not increasing volume", 1.0);
        }
        return new BounceRules("continue", "ok", 1.0);
    }

    /**
     * Read-only mirror of {@link #checkBounceRules} for the deliverability dashboard: same 7-day window,
     * same thresholds and action names, but it NEVER writes state (no pausing, no saves) — safe to call
     * on every page render. {@code pausedUntil} reflects an already-persisted pause window if one is active.
     */
    public Deliverability deliverability(String profileKey) {
        Instant now = Instant.now();
        Instant pausedUntil = stateRepo.findByProfileKey(profileKey).map(OutreachAccountStateEntity::getPausedUntil).orElse(null);
        Instant sentFrom = now.minus(WINDOW_DAYS, ChronoUnit.DAYS);
        long sent = count(profileKey, Criteria.where("sentAt").gte(sentFrom));
        long bounced = count(profileKey, Criteria.where("sentAt").gte(sentFrom).and("status").is("BOUNCED"));
        double rate = sent >= MIN_SAMPLE ? (bounced / (double) sent) * 100.0 : 0.0;

        if (pausedUntil != null && now.isBefore(pausedUntil)) {
            long mins = ChronoUnit.MINUTES.between(now, pausedUntil);
            return new Deliverability(sent, bounced, rate, "pause", "Paused for " + mins + " more min", 0.0, pausedUntil, pausedUntil);
        }
        // For a rate-driven throttle, the state eases once the OLDEST in-window bounce ages out (its
        // send drops past the 7-day window). Estimate only — assumes no further bounces.
        Instant easesAt = null;
        if (bounced > 0 && rate > RATE_FREEZE) {
            Instant oldest = oldestBounceSentAt(profileKey, sentFrom);
            if (oldest != null) {
                easesAt = oldest.plus(WINDOW_DAYS, ChronoUnit.DAYS);
            }
        }
        if (sent >= MIN_SAMPLE) {
            if (rate > RATE_STOP) {
                return new Deliverability(sent, bounced, rate, "stop",
                        String.format("Bounce rate %.1f%% > 5%%", rate), 0.0, null, easesAt);
            }
            if (rate > RATE_REDUCE) {
                return new Deliverability(sent, bounced, rate, "reduce",
                        String.format("Bounce rate %.1f%% > 3%%", rate), 0.5, null, easesAt);
            }
            if (rate > RATE_FREEZE) {
                return new Deliverability(sent, bounced, rate, "freeze",
                        String.format("Bounce rate %.1f%% > 2%%", rate), 1.0, null, easesAt);
            }
        }
        long recentBounces = mongo.count(new Query(new Criteria().andOperator(
                Criteria.where("profileKey").is(profileKey),
                Criteria.where("status").is("BOUNCED"),
                Criteria.where("sentAt").gte(sentFrom),
                Criteria.where("bouncedAt").gte(now.minus(24, ChronoUnit.HOURS)))), "outreach_sent_emails");
        if (recentBounces >= 2) {
            return new Deliverability(sent, bounced, rate, "pause", "2 recent hard bounces", 0.0, null, now.plus(24, ChronoUnit.HOURS));
        }
        if (recentBounces == 1) {
            return new Deliverability(sent, bounced, rate, "freeze", "1 recent hard bounce", 1.0, null, now.plus(24, ChronoUnit.HOURS));
        }
        return new Deliverability(sent, bounced, rate, "continue", "OK", 1.0, null, null);
    }

    /** The earliest {@code sentAt} among still-in-window bounced sends (drives the recovery estimate). */
    private Instant oldestBounceSentAt(String profileKey, Instant sentFrom) {
        Query q = new Query(new Criteria().andOperator(
                Criteria.where("profileKey").is(profileKey),
                Criteria.where("status").is("BOUNCED"),
                Criteria.where("sentAt").gte(sentFrom)))
                .with(Sort.by(Sort.Direction.ASC, "sentAt")).limit(1);
        q.fields().include("sentAt");
        Document d = mongo.findOne(q, Document.class, "outreach_sent_emails");
        return d != null && d.get("sentAt") instanceof Date dt ? dt.toInstant() : null;
    }

    private long count(String profileKey, Criteria criteria) {
        return mongo.count(new Query(criteria.and("profileKey").is(profileKey)), "outreach_sent_emails");
    }

    public void save(OutreachAccountStateEntity s) {
        stateRepo.save(s);
    }
}
