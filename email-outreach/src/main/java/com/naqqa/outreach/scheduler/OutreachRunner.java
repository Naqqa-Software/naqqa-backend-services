package com.naqqa.outreach.scheduler;

import com.naqqa.outreach.config.OutreachProperties;
import com.naqqa.outreach.engine.FollowupService;
import com.naqqa.outreach.engine.InboxSyncService;
import com.naqqa.outreach.engine.OutreachEngine;
import com.naqqa.outreach.entity.OutreachProfileEntity;
import com.naqqa.outreach.logging.OutreachLogAppender;
import com.naqqa.outreach.service.OutreachSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs a profile's day asynchronously so the scheduler thread isn't held for the whole working
 * window. Order: sync inbox (stop replied/bounced/unsubscribed) → send due follow-ups → send new
 * initial emails, all under the shared daily cap + pacing. Re-entrancy guarded per profile so the
 * 9am cron, the catch-up tick and the startup catch-up can't run the same profile twice at once.
 * Every log line is tagged with the profile via MDC for the per-profile log files.
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

    /** Profiles with a run in flight (prevents concurrent double-sends). */
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    @Async
    public void runDaily(OutreachProfileEntity profile) {
        if (!running.add(profile.getKey())) {
            return; // already sending for this profile — the persisted sentToday counter drives the cap
        }
        MDC.put(OutreachLogAppender.MDC_KEY, profile.getKey());
        try {
            log.info("[{}] outreach run starting.", profile.getKey());
            // Inbox sync always runs (detect replies/bounces even while sending is paused).
            inbox.sync(profile);
            if (!settings.isSendingActive()) {
                log.info("[{}] sending is OFF (admin toggle) — skipping follow-ups + initial sends.",
                        profile.getKey());
                return;
            }
            // Initial sends first (capped at the non-follow-up share of the daily cap), then
            // follow-ups fill whatever is left — so no-new-leads days go to 2nd/3rd emails.
            engine.runProfile(profile);
            if (props.isFollowupsEnabled()) {
                followups.dispatchDue(profile);
            } else {
                log.info("[{}] follow-ups disabled — initial emails only.", profile.getKey());
            }
            log.info("[{}] outreach run finished.", profile.getKey());
        } catch (Exception e) {
            log.error("[{}] daily run failed", profile.getKey(), e);
        } finally {
            running.remove(profile.getKey());
            MDC.remove(OutreachLogAppender.MDC_KEY);
        }
    }

    @Async
    public void syncOnly(OutreachProfileEntity profile) {
        MDC.put(OutreachLogAppender.MDC_KEY, profile.getKey());
        try {
            inbox.sync(profile);
        } catch (Exception e) {
            log.warn("[{}] inbox sync failed: {}", profile.getKey(), e.getMessage());
        } finally {
            MDC.remove(OutreachLogAppender.MDC_KEY);
        }
    }
}
