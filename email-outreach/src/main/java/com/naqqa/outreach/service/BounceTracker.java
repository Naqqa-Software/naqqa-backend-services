package com.naqqa.outreach.service;

import com.naqqa.outreach.entity.OutreachAccountStateEntity;
import com.naqqa.outreach.repository.OutreachAccountStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Bounce protection — faithful port of checkBounceRules(): rolling last-100 bounce-rate thresholds
 * (>5% stop, >3% reduce ×0.5, >2% freeze) plus daily hard-bounce rules (≥2 → 24h pause, 1 → freeze).
 * Also owns the per-profile rolling send/bounce window state.
 */
@Service
@RequiredArgsConstructor
public class BounceTracker {

    /** action ∈ pause|stop|reduce|freeze|continue. */
    public record BounceRules(String action, String reason, double limitMultiplier) {
    }

    private final OutreachAccountStateRepository stateRepo;

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

        int total = s.getRecentEmails().size();
        long bounced = s.getRecentEmails().stream().filter("bounced"::equals).count();
        double rate = total >= 20 ? (bounced / (double) total) * 100.0 : 0.0;
        if (total >= 20) {
            if (rate > 5) {
                return new BounceRules("stop", String.format("Bounce rate %.1f%% > 5%% — campaign stopped", rate), 0.0);
            }
            if (rate > 3) {
                return new BounceRules("reduce", String.format("Bounce rate %.1f%% > 3%% — reducing volume 50%%", rate), 0.5);
            }
            if (rate > 2) {
                return new BounceRules("freeze", String.format("Bounce rate %.1f%% > 2%% — scaling frozen", rate), 1.0);
            }
        }

        int todayBounces = s.getDailyBounces().getOrDefault(OutreachTime.todayKey(), 0);
        if (todayBounces >= 2) {
            s.setPausedUntil(now.plus(24, ChronoUnit.HOURS));
            stateRepo.save(s);
            return new BounceRules("pause", "2 hard bounces today — account paused 24h", 0.0);
        }
        if (todayBounces == 1) {
            return new BounceRules("freeze", "1 hard bounce today — not increasing volume", 1.0);
        }
        return new BounceRules("continue", "ok", 1.0);
    }

    public void recordSent(OutreachAccountStateEntity s) {
        push(s, "sent");
        stateRepo.save(s);
    }

    public void recordBounce(OutreachAccountStateEntity s) {
        String today = OutreachTime.todayKey();
        s.getDailyBounces().merge(today, 1, Integer::sum);
        push(s, "bounced");
        stateRepo.save(s);
    }

    private void push(OutreachAccountStateEntity s, String outcome) {
        s.getRecentEmails().add(outcome);
        while (s.getRecentEmails().size() > 100) {
            s.getRecentEmails().remove(0);
        }
    }

    public void save(OutreachAccountStateEntity s) {
        stateRepo.save(s);
    }
}
