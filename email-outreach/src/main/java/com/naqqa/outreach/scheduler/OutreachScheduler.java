package com.naqqa.outreach.scheduler;

import com.naqqa.outreach.config.OutreachProperties;
import com.naqqa.outreach.repository.OutreachProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily 09:00 kickoff for every enabled sender profile, plus an hourly inbox sync so replies /
 * bounces stop sequences during the day. Requires {@code @EnableScheduling} on the host app
 * (naqqa-os-be already enables it).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutreachScheduler {

    private final OutreachProperties props;
    private final OutreachProfileRepository profiles;
    private final OutreachRunner runner;

    /** 09:00 daily — starts both profiles. */
    @Scheduled(cron = "${naqqa.outreach.daily-cron:0 0 9 * * *}")
    public void dailyKickoff() {
        if (!props.isEnabled()) {
            return;
        }
        var enabled = profiles.findAllByEnabledTrue();
        log.info("Outreach daily kickoff for {} profile(s).", enabled.size());
        enabled.forEach(runner::runDaily);
    }

    /** Hourly inbox sync (bounces + replies) so follow-ups/sends stop promptly. */
    @Scheduled(cron = "0 0 * * * *")
    public void hourlyInboxSync() {
        if (!props.isEnabled()) {
            return;
        }
        profiles.findAllByEnabledTrue().forEach(runner::syncOnly);
    }
}
