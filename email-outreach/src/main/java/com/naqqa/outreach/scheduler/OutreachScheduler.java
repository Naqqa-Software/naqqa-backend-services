package com.naqqa.outreach.scheduler;

import com.naqqa.outreach.config.OutreachProperties;
import com.naqqa.outreach.repository.OutreachProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives every enabled sender profile. Three triggers, all funnelling into the re-entrancy-guarded
 * {@code runner.runDaily} (idempotent — the persisted {@code sentToday} counter caps each profile):
 * <ul>
 *   <li><b>09:00 cron</b> — the normal daily start;</li>
 *   <li><b>catch-up tick</b> (every {@code catch-up-tick-ms}, default 30 min) — picks the day up if
 *       the app started mid-day or was down during the 9–18 window, and re-syncs the inbox;</li>
 *   <li><b>startup</b> — same catch-up the moment the app is ready, so recovery isn't delayed.</li>
 * </ul>
 * The engine itself only sends inside the working window/day, so a 10pm start just syncs and waits.
 * Requires {@code @EnableScheduling} on the host (naqqa-os-be enables it).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutreachScheduler {

    private final OutreachProperties props;
    private final OutreachProfileRepository profiles;
    private final OutreachRunner runner;

    /** 09:00 daily — starts every enabled profile. */
    @Scheduled(cron = "${naqqa.outreach.daily-cron:0 0 9 * * *}")
    public void dailyKickoff() {
        run("daily kickoff");
    }

    /** Periodic catch-up — resumes the day's sends after a mid-day start or downtime. */
    @Scheduled(fixedDelayString = "${naqqa.outreach.catch-up-tick-ms:1800000}", initialDelay = 60000)
    public void catchUpTick() {
        run(null);
    }

    /** Immediate catch-up on boot (don't wait for the first tick). */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        run("startup catch-up");
    }

    private void run(String label) {
        if (!props.isEnabled()) {
            return;
        }
        var enabled = profiles.findAllByEnabledTrue();
        if (label != null) {
            log.info("Outreach {} for {} profile(s).", label, enabled.size());
        }
        enabled.forEach(runner::runDaily);
    }
}
