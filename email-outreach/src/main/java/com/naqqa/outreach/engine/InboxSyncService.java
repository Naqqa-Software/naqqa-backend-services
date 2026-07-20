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
import com.naqqa.outreach.service.OllamaClient;
import com.naqqa.outreach.service.OutreachConstants;
import com.naqqa.outreach.service.OutreachLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
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
    private final OllamaClient ollama;

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
        log.info("📥 [{}] inbox scan: {} message(s) in window.", profile.getKey(), inbound.size());
        if (inbound.isEmpty()) {
            return;
        }
        OutreachAccountStateEntity state = bounce.state(profile.getKey());
        boolean stateDirty = false;

        // Phase 1: triage each UNPROCESSED message. Obvious bounces + keyword unsubscribes are handled
        // immediately; already-classified matches just backfill text; everything else that matched a
        // still-open thread is QUEUED for AI batch classification.
        List<Pending> pending = new ArrayList<>();
        for (ImapReader.Inbound m : inbound) {
            if (state.getProcessedUids().contains(m.uid())) {
                continue; // already processed in a prior sync — never sent to the AI again
            }
            String fromLower = m.fromEmail() == null ? "" : m.fromEmail().toLowerCase();
            String subjLower = m.subject() == null ? "" : m.subject().toLowerCase();
            String bodyLower = m.bodyPreview() == null ? "" : m.bodyPreview().toLowerCase();
            // A bounce if it's from a mail daemon, or the sender/subject signals a delivery failure.
            // Body phrases only count from an AUTOMATED (daemon/no-reply) sender, so a human reply
            // that merely quotes "couldn't be delivered" isn't mistaken for a bounce.
            boolean fromDaemon = fromLower.contains("mailer-daemon") || fromLower.contains("maildaemon")
                    || fromLower.contains("mail-daemon") || fromLower.contains("postmaster")
                    || fromLower.contains("mail delivery") || fromLower.contains("no-reply")
                    || fromLower.contains("noreply") || fromLower.contains("do-not-reply");
            boolean isBounce =
                    OutreachConstants.BOUNCE_PATTERNS.stream().anyMatch((fromLower + " " + subjLower)::contains)
                    || (fromDaemon && OutreachConstants.BODY_BOUNCE_PATTERNS.stream().anyMatch(bodyLower::contains));
            boolean transient_ = OutreachConstants.TRANSIENT_PATTERNS.stream().anyMatch(subjLower::contains);

            // Automated delivery failure → BOUNCED, no AI needed.
            if (isBounce && !transient_) {
                markBouncedByBody(m.subject(), m.bodyPreview());
                markProcessed(state, m.uid());
                stateDirty = true;
                continue;
            }

            SentEmailEntity matched = matchThread(m);
            if (matched == null) {
                continue; // unrelated mail — leave unprocessed
            }
            if (matched.getStatus() != SendStatus.SENT) {
                // Already classified — just backfill the reply text if missing (no AI, no status change).
                stopSequence(matched.getThreadKey(), matched.getStatus() == SendStatus.UNSUBSCRIBED,
                        m.subject(), m.bodyPreview());
                markProcessed(state, m.uid());
                stateDirty = true;
                continue;
            }
            if (containsUnsub(m.subject()) || containsUnsub(m.bodyPreview())) {
                classify(profile, matched, m, "UNSUBSCRIBE"); // clear keyword unsubscribe, no AI
                markProcessed(state, m.uid());
                stateDirty = true;
                continue;
            }
            pending.add(new Pending(m, matched)); // ambiguous → needs the AI classifier
        }

        // Phase 2: classify the queued replies with Ollama in BATCHES (default 5 per call).
        if (!pending.isEmpty()) {
            int batch = Math.max(1, props.getAiClassifyBatchSize());
            log.info("🔎 [{}] AI-classifying {} unprocessed repl{} in batches of {}...",
                    profile.getKey(), pending.size(), pending.size() == 1 ? "y" : "ies", batch);
            for (int start = 0; start < pending.size(); start += batch) {
                List<Pending> chunk = pending.subList(start, Math.min(start + batch, pending.size()));
                List<OllamaClient.ReplyInput> inputs = chunk.stream()
                        .map(p -> new OllamaClient.ReplyInput(p.m().subject(), p.m().bodyPreview())).toList();
                List<String> cats = ollama.classifyReplies(inputs);
                for (int j = 0; j < chunk.size(); j++) {
                    Pending p = chunk.get(j);
                    classify(profile, p.matched(), p.m(), j < cats.size() ? cats.get(j) : "OTHER");
                    markProcessed(state, p.m().uid());
                }
                stateDirty = true;
            }
        }

        if (stateDirty) {
            bounce.save(state);
        }
    }

    /** A matched, still-open reply queued for AI batch classification. */
    private record Pending(ImapReader.Inbound m, SentEmailEntity matched) {
    }

    private void markProcessed(OutreachAccountStateEntity state, long uid) {
        state.getProcessedUids().add(uid);
        while (state.getProcessedUids().size() > 1000) {
            state.getProcessedUids().remove(0);
        }
    }

    /** Apply the AI/keyword category to a still-open (SENT) matched thread + log it. */
    private void classify(OutreachProfileEntity profile, SentEmailEntity matched,
                          ImapReader.Inbound m, String category) {
        switch (category) {
            case "BOUNCE" -> {
                markThreadBounced(matched, m.subject(), m.bodyPreview());
                log.info("📭 [{}] [CLASSIFY] {} ({}) -> BOUNCED (send error).",
                        profile.getKey(), m.fromEmail(), matched.getCompanyName());
                logReply(profile, matched, m, "BOUNCE");
            }
            case "UNSUBSCRIBE" -> {
                stopSequence(matched.getThreadKey(), true, m.subject(), m.bodyPreview());
                log.info("🚫 [{}] [CLASSIFY] {} ({}) -> UNSUBSCRIBED.",
                        profile.getKey(), m.fromEmail(), matched.getCompanyName());
                logReply(profile, matched, m, "UNSUBSCRIBE");
            }
            default -> {
                stopSequence(matched.getThreadKey(), false, m.subject(), m.bodyPreview());
                log.info("✉️ [{}] [CLASSIFY] {} ({}) -> RESPONDED (manual triage).",
                        profile.getKey(), m.fromEmail(), matched.getCompanyName());
                logReply(profile, matched, m, "RESPONDED");
            }
        }
    }

    private void logReply(OutreachProfileEntity profile, SentEmailEntity matched,
                          ImapReader.Inbound m, String category) {
        logService.recordToStream(profile.getKey(), "replies", String.format(
                "%s from=%s company=\"%s\" subject=\"%s\"%n%s%n----", category, m.fromEmail(),
                safe(matched.getCompanyName()), safe(m.subject()),
                m.bodyPreview() == null ? "" : m.bodyPreview().trim()));
    }

    /** AI-detected delivery error — mark the matched thread's open rows BOUNCED (+ company). */
    private void markThreadBounced(SentEmailEntity matched, String subject, String body) {
        String reply = buildReplyText(subject, body);
        for (SentEmailEntity s : sentRepo.findByThreadKey(matched.getThreadKey())) {
            if (s.getStatus() == SendStatus.SENT) {
                s.setStatus(SendStatus.BOUNCED);
                s.setBouncedAt(Instant.now());
                if (reply != null && (s.getResponseText() == null || s.getResponseText().isBlank())) {
                    s.setResponseText(reply);
                }
                sentRepo.save(s);
                leads.markBounced(s.getCompanyId());
            }
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

    private void markBouncedByBody(String subject, String body) {
        if (body == null || body.isBlank()) {
            return;
        }
        // The mailer-daemon body IS the "why not delivered" reason — save it so it's readable in the drawer.
        String reason = buildReplyText(subject, body);
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        var matcher = OutreachConstants.EMAIL_SCRAPE.matcher(body);
        while (matcher.find()) {
            seen.add(matcher.group());
        }
        boolean any = false;
        for (String addr : seen) {
            for (SentEmailEntity s : sentRepo.findByToEmailIgnoreCase(addr)) {
                if (s.getStatus() == SendStatus.SENT) {
                    s.setStatus(SendStatus.BOUNCED);
                    s.setBouncedAt(Instant.now());
                    if (reason != null && (s.getResponseText() == null || s.getResponseText().isBlank())) {
                        s.setResponseText(reason);
                    }
                    sentRepo.save(s);
                    // Flag the company too so it drops out of extraction/sending.
                    leads.markBounced(s.getCompanyId());
                    log.info("📭 Bounce: {} ({}) — email + company marked BOUNCED.",
                            s.getToEmail(), s.getCompanyName());
                    any = true;
                }
            }
        }
        if (!any) {
            // Detected a delivery failure but couldn't tie it to a sent row (address we never sent to,
            // or already handled). Logged so it's visible rather than silently dropped.
            log.warn("📭 Bounce detected but no open sent email matched (recipients seen: {}).", seen);
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
