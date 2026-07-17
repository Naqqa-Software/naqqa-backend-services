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
import com.naqqa.outreach.service.OutreachLogService;
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
    private final OutreachLogService logService;

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
                boolean captured = stopSequence(matched.getThreadKey(), unsub, m.subject(), m.bodyPreview());
                if (captured) {
                    log.info("[{}] reply from {} ({}) -> {}", profile.getKey(), m.fromEmail(),
                            matched.getCompanyName(), unsub ? "UNSUBSCRIBED" : "RESPONDED");
                    // Dedicated, readable replies log ({logDir}/{profile}/replies-{day}.txt).
                    logService.recordToStream(profile.getKey(), "replies", String.format(
                            "REPLY from=%s company=\"%s\" subject=\"%s\"%n%s%n----",
                            m.fromEmail(), safe(matched.getCompanyName()), safe(m.subject()),
                            m.bodyPreview() == null ? "" : m.bodyPreview().trim()));
                }
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
            List<SentEmailEntity> byAddr = sentRepo.findByToEmailIgnoreCase(m.fromEmail().trim());
            if (!byAddr.isEmpty()) {
                return byAddr.get(0);
            }
        }
        return null;
    }

    /** @return true if this reply changed anything (status flip or reply-text backfill) — i.e. it's news. */
    private boolean stopSequence(String threadKey, boolean unsubscribe, String replySubject, String replyBody) {
        List<SentEmailEntity> thread = sentRepo.findByThreadKey(threadKey);
        SendStatus target = unsubscribe ? SendStatus.UNSUBSCRIBED : SendStatus.RESPONDED;
        String reply = buildReplyText(replySubject, replyBody);
        boolean anyChange = false;
        for (SentEmailEntity s : thread) {
            boolean changed = false;
            // Still-open row: mark it replied/unsubscribed and stop the sequence.
            if (s.getStatus() == SendStatus.SENT) {
                s.setStatus(target);
                s.setRepliedAt(Instant.now());
                changed = true;
            }
            // Save/BACKFILL what the prospect actually replied — also for rows already marked
            // RESPONDED (e.g. before this feature existed) so a re-sync fills the missing reply text.
            if (reply != null && (s.getResponseText() == null || s.getResponseText().isBlank())
                    && isReplyOutcome(s.getStatus())) {
                s.setResponseText(reply);
                if (s.getRepliedAt() == null) {
                    s.setRepliedAt(Instant.now());
                }
                changed = true;
            }
            if (changed) {
                sentRepo.save(s);
                anyChange = true;
            }
        }
        if (anyChange) {
            log.info("Sequence {} stopped ({}).", threadKey, target);
        }
        return anyChange;
    }

    /** One-line-safe: strip newlines/quotes so a value can't break the replies-log line format. */
    private String safe(String v) {
        return v == null ? "" : v.replaceAll("[\\r\\n\\t]+", " ").replace("\"", "'").trim();
    }

    /** True for statuses that represent an inbound reply (so the reply text belongs on the row). */
    private boolean isReplyOutcome(SendStatus status) {
        return status == SendStatus.RESPONDED || status == SendStatus.UNSUBSCRIBED
                || status == SendStatus.POSITIVE || status == SendStatus.NEGATIVE
                || status == SendStatus.REPLIED;
    }

    /** Compact, readable snapshot of the reply (subject + trimmed body) for the admin drawer. */
    private String buildReplyText(String subject, String body) {
        String b = body == null ? "" : body.trim();
        if (b.length() > 4000) {
            b = b.substring(0, 4000) + "…";
        }
        String s = subject == null ? "" : subject.trim();
        if (s.isBlank() && b.isBlank()) {
            return null;
        }
        return (s.isBlank() ? "" : "Subject: " + s + "\n\n") + b;
    }

    private void markBouncedByBody(String body) {
        if (body == null || body.isBlank()) {
            return;
        }
        var matcher = OutreachConstants.EMAIL_SCRAPE.matcher(body);
        while (matcher.find()) {
            for (SentEmailEntity s : sentRepo.findByToEmailIgnoreCase(matcher.group())) {
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
