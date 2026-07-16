package com.naqqa.outreach.scheduler;

import com.naqqa.outreach.config.OutreachProperties;
import com.naqqa.outreach.engine.FollowupService;
import com.naqqa.outreach.engine.InboxSyncService;
import com.naqqa.outreach.engine.OutreachEngine;
import com.naqqa.outreach.entity.OutreachProfileEntity;
import com.naqqa.outreach.service.OutreachSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Runs a profile's day asynchronously so the scheduler thread isn't held for the whole working
 * window. Order: sync inbox (stop replied/bounced/unsubscribed) → send due follow-ups → send new
 * initial emails, all under the shared daily cap + pacing.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutreachRunner {

    private final InboxSyncService inbox;
    private final FollowupService followups;
    private final OutreachEngine engine;
    private final OutreachSettingsService settings;
    private final OutreachProperties props;

    @Async
    public void runDaily(OutreachProfileEntity profile) {
        log.info("[{}] daily outreach run starting.", profile.getKey());
        try {
            // Inbox sync always runs (detect replies/bounces even while sending is paused).
            inbox.sync(profile);
            if (!settings.isSendingActive()) {
                log.info("[{}] sending is OFF (admin toggle) — skipping follow-ups + initial sends.",
                        profile.getKey());
                return;
            }
            if (props.isFollowupsEnabled()) {
                followups.dispatchDue(profile);
            } else {
                log.info("[{}] follow-ups disabled — sending a single initial email only.", profile.getKey());
            }
            engine.runProfile(profile);
        } catch (Exception e) {
            log.error("[{}] daily run failed", profile.getKey(), e);
        }
        log.info("[{}] daily outreach run finished.", profile.getKey());
    }

    @Async
    public void syncOnly(OutreachProfileEntity profile) {
        try {
            inbox.sync(profile);
        } catch (Exception e) {
            log.warn("[{}] inbox sync failed: {}", profile.getKey(), e.getMessage());
        }
    }
}
