package com.naqqa.outreach.engine;

import com.naqqa.outreach.entity.OutreachAccountStateEntity;
import com.naqqa.outreach.entity.OutreachProfileEntity;
import com.naqqa.outreach.entity.SendStatus;
import com.naqqa.outreach.entity.SentEmailEntity;
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
 * detects replies / unsubscribes, stopping the follow-up sequence for that lead.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InboxSyncService {

    private static final long WINDOW_MS = 48L * 60 * 60 * 1000;

    private final ImapReader imap;
    private final BounceTracker bounce;
    private final SentEmailRepository sentRepo;
    private final LeadService leads;

    public void sync(OutreachProfileEntity profile) {
        List<ImapReader.Inbound> inbound = imap.readRecent(profile, System.currentTimeMillis() - WINDOW_MS);
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
                    bounce.recordBounce(state);
                    state.getProcessedUids().add(m.uid());
                    while (state.getProcessedUids().size() > 1000) {
                        state.getProcessedUids().remove(0);
                    }
                    stateDirty = true;
                    markBouncedByBody(m.bodyPreview());
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
        SendStatus target = unsubscribe ? SendStatus.UNSUBSCRIBED : SendStatus.REPLIED;
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
