package com.naqqa.outreach.engine;

import com.naqqa.outreach.entity.OutreachAccountStateEntity;
import com.naqqa.outreach.entity.OutreachProfileEntity;
import com.naqqa.outreach.entity.SendStatus;
import com.naqqa.outreach.entity.SentEmailEntity;
import com.naqqa.outreach.config.OutreachProperties;
import com.naqqa.outreach.repository.SentEmailRepository;
import com.naqqa.outreach.service.BounceTracker;
import com.naqqa.outreach.service.ImapReader;
import com.naqqa.outreach.service.LeadService;
import com.naqqa.outreach.service.OutreachConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Reads each profile's inbox and (a) records hard bounces into the throttling state and (b)
 * detects replies / unsubscribes, stopping the follow-up sequence for that lead. The routine sync
 * looks back {@code inboxWindowHours}; {@link #deepScan} rescans a much larger window on demand to
 * catch replies to older campaigns.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InboxSyncService {

    private final ImapReader imap;
    private final BounceTracker bounce;
    private final SentEmailRepository sentRepo;
    private final LeadService leads;
    private final OutreachProperties props;

    /** Routine sync over the configured recent window. */
    public void sync(OutreachProfileEntity profile) {
        syncSince(profile, System.currentTimeMillis() - props.getInboxWindowHours() * 3600_000L);
    }

    /** One-off deep scan over the last {@code days} — catches replies to old (e.g. seeded) campaigns. */
    public void deepScan(OutreachProfileEntity profile, int days) {
        log.info("[{}] deep inbox scan over last {} days.", profile.getKey(), days);
        syncSince(profile, System.currentTimeMillis() - days * 86_400_000L);
    }

    private void syncSince(OutreachProfileEntity profile, long sinceMillis) {
        List<ImapReader.Inbound> inbound = imap.readRecent(profile, sinceMillis);
        if (inbound.isEmpty()) {
            return;
        }
        OutreachAccountStateEntity state = bounce.state(profile.getKey());
        boolean stateDirty = false;

        for (ImapReader.Inbound m : inbound) {
            String hay = (m.fromEmail() + " " + m.subject()).toLowerCase();
            boolean isBounce = OutreachConstants.BOUNCE_PATTERNS.stream().anyMatch(hay::contains);
            boolean transient_ = OutreachConstants.TRANSIENT_PATTERNS.stream()
                    .anyMatch(p -> m.subject().toLowerCase().contains(p));

            if (isBounce && !transient_) {
                if (!state.getProcessedUids().contains(m.uid())) {
                    // Mark the email + company BOUNCED. The bounce THROTTLE reads these BOUNCED
                    // records back from the DB (real data), so no separate counter is kept here.
                    markBouncedByBody(m.bodyPreview());
                    state.getProcessedUids().add(m.uid());
                    while (state.getProcessedUids().size() > 1000) {
                        state.getProcessedUids().remove(0);
                    }
                    stateDirty = true;
                }
                continue;
            }

            // Reply detection: match In-Reply-To/References to one of our sent Message-IDs, else by sender.
            SentEmailEntity matched = matchThread(m);
            if (matched != null) {
                boolean unsub = containsUnsub(m.subject()) || containsUnsub(m.bodyPreview());
                stopSequence(matched.getThreadKey(), unsub);
            }
        }
        if (stateDirty) {
            bounce.save(state);
        }
    }

    private SentEmailEntity matchThread(ImapReader.Inbound m) {
        for (String ref : m.inReplyTo()) {
            List<SentEmailEntity> byId = sentRepo.findByMessageId(ref);
            if (!byId.isEmpty()) {
                return byId.get(0);
            }
        }
        if (m.fromEmail() != null && !m.fromEmail().isBlank()) {
            List<SentEmailEntity> byAddr = sentRepo.findByToEmail(m.fromEmail());
            if (!byAddr.isEmpty()) {
                return byAddr.get(0);
            }
        }
        return null;
    }

    private void stopSequence(String threadKey, boolean unsubscribe) {
        List<SentEmailEntity> thread = sentRepo.findByThreadKey(threadKey);
        SendStatus target = unsubscribe ? SendStatus.UNSUBSCRIBED : SendStatus.RESPONDED;
        for (SentEmailEntity s : thread) {
            if (s.getStatus() == SendStatus.SENT) {
                s.setStatus(target);
                s.setRepliedAt(Instant.now());
                sentRepo.save(s);
            }
        }
        log.info("Sequence {} stopped ({}).", threadKey, target);
    }

    private void markBouncedByBody(String body) {
        if (body == null || body.isBlank()) {
            return;
        }
        var matcher = OutreachConstants.EMAIL_SCRAPE.matcher(body);
        while (matcher.find()) {
            for (SentEmailEntity s : sentRepo.findByToEmail(matcher.group().toLowerCase())) {
                if (s.getStatus() == SendStatus.SENT) {
                    s.setStatus(SendStatus.BOUNCED);
                    s.setBouncedAt(Instant.now());
                    sentRepo.save(s);
                    // Flag the company too so it drops out of extraction/sending.
                    leads.markBounced(s.getCompanyId());
                    log.info("Bounce detected for {} — email + company marked BOUNCED.", s.getToEmail());
                }
            }
        }
    }

    private boolean containsUnsub(String text) {
        if (text == null) {
            return false;
        }
        String t = text.toLowerCase();
        return t.contains("unsubscribe") || t.contains("remove me")
                || t.contains("stop emailing") || t.contains("stop contacting") || t.contains("opt out");
    }
}
