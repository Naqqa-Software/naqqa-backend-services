package com.naqqa.outreach.engine;

import com.naqqa.outreach.config.OutreachProperties;
import com.naqqa.outreach.entity.OutreachAccountStateEntity;
import com.naqqa.outreach.entity.OutreachProfileEntity;
import com.naqqa.outreach.entity.SendStatus;
import com.naqqa.outreach.entity.SentEmailEntity;
import com.naqqa.outreach.repository.SentEmailRepository;
import com.naqqa.outreach.service.BounceTracker;
import com.naqqa.outreach.service.DailyLimitService;
import com.naqqa.outreach.service.GmailSender;
import com.naqqa.outreach.service.OutreachTime;
import com.naqqa.outreach.service.SafetyGates;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Sends due follow-up steps as threaded replies. A sequence advances 1 → 2 → 3 (max = maxFollowups
 * follow-ups) with configured day gaps, and is stopped by InboxSyncService once the lead replies,
 * bounces or unsubscribes (those records are no longer status SENT, so they're skipped here).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FollowupService {

    private final OutreachProperties props;
    private final SentEmailRepository sentRepo;
    private final GmailSender sender;
    private final SafetyGates gates;
    private final BounceTracker bounce;
    private final DailyLimitService limits;

    public void dispatchDue(OutreachProfileEntity profile) {
        OutreachAccountStateEntity state = bounce.state(profile.getKey());
        limits.resetIfNewDay(state);
        int cap = limits.effectiveCap(profile, state);
        if (cap <= 0) {
            return;
        }

        // Latest step per thread (only sequences still in SENT state are eligible).
        Map<String, SentEmailEntity> latest = new HashMap<>();
        for (SentEmailEntity s : sentRepo.findByProfileKeyAndStatus(profile.getKey(), SendStatus.SENT)) {
            latest.merge(s.getThreadKey(), s, (a, b) -> a.getSequenceStep() >= b.getSequenceStep() ? a : b);
        }

        for (SentEmailEntity last : latest.values()) {
            if (state.getSentToday() >= cap
                    || !OutreachTime.isWorkingHours(props.getWorkStartHour(), props.getWorkEndHour())) {
                break;
            }
            int step = last.getSequenceStep();
            if (step > props.getMaxFollowups() || last.getSentAt() == null) {
                continue;
            }
            List<Integer> delays = props.getFollowupDelaysDays();
            int delayDays = delays.get(Math.min(step - 1, delays.size() - 1));
            if (ChronoUnit.DAYS.between(last.getSentAt(), Instant.now()) < delayDays) {
                continue;
            }

            String subject = last.getSubject();
            String body = followupBody(step);
            SafetyGates.Gate gate = gates.validateGeneratedEmail(subject, body, last.getCompanyName());
            if (!gate.valid()) {
                continue;
            }
            try {
                GmailSender.SendResult res = sender.send(profile, last.getToEmail(), subject, body, last.getMessageId());
                SentEmailEntity next = new SentEmailEntity();
                next.setProfileKey(profile.getKey());
                next.setFromEmail(profile.getFromEmail());
                next.setCompanyId(last.getCompanyId());
                next.setCompanyName(last.getCompanyName());
                next.setToEmail(last.getToEmail());
                next.setToName(last.getToName());
                next.setSubject(subject);
                next.setBody(body);
                next.setSequenceStep(step + 1);
                next.setThreadKey(last.getThreadKey());
                next.setMessageId(res.messageId());
                next.setInReplyTo(last.getMessageId());
                next.setStatus(SendStatus.SENT);
                next.setSentAt(Instant.now());
                sentRepo.save(next);

                bounce.recordSent(state);
                state.setSentToday(state.getSentToday() + 1);
                bounce.save(state);
                log.info("[{}] sent follow-up step {} -> {}", profile.getKey(), step + 1, last.getToEmail());
                Thread.sleep(ThreadLocalRandom.current().nextInt(
                        props.getMinDelaySeconds(), props.getMaxDelaySeconds() + 1) * 1000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("[{}] follow-up failed for {}: {}", profile.getKey(), last.getToEmail(), e.getMessage());
            }
        }
    }

    /** Plain, safe follow-up bodies (pass the same content gate; start with "hello", no risky phrases). */
    private String followupBody(int step) {
        if (step == 1) {
            return "Hello,\n\nFollowing up on my previous message in case it is relevant for your team. "
                    + "If there is interest in additional development capacity, I am happy to share more.\n\n"
                    + "Would a short reply help?";
        }
        return "Hello,\n\nThis is my last note on this. If dedicated developers or a small team extension "
                + "could be useful later, feel free to keep my email. Otherwise I will not follow up again.\n\n"
                + "Thank you for your time.";
    }
}
