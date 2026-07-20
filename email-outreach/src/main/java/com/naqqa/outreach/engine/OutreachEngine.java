package com.naqqa.outreach.engine;

import com.naqqa.outreach.config.OutreachProperties;
import com.naqqa.outreach.entity.LeadEntity;
import com.naqqa.outreach.entity.OutreachAccountStateEntity;
import com.naqqa.outreach.entity.OutreachProfileEntity;
import com.naqqa.outreach.entity.SendStatus;
import com.naqqa.outreach.entity.SentEmailEntity;
import com.naqqa.outreach.repository.SentEmailRepository;
import com.naqqa.outreach.service.ApolloClient;
import com.naqqa.outreach.service.BounceTracker;
import com.naqqa.outreach.service.CompanyScraper;
import com.naqqa.outreach.service.DailyLimitService;
import com.naqqa.outreach.service.EmailValidator;
import com.naqqa.outreach.service.GmailSender;
import com.naqqa.outreach.service.LeadService;
import com.naqqa.outreach.service.OllamaClient;
import com.naqqa.outreach.service.OutreachLogService;
import com.naqqa.outreach.service.OutreachTime;
import com.naqqa.outreach.service.SafetyGates;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Per-profile daily send loop — the faithful orchestration of start_alex.js/start_radu.js:
 * working-window + warm-up cap + bounce throttle, then pick lead → find email → validate →
 * scrape → quality gate → generate → post-gen gate → send → record → pace.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutreachEngine {

    private final OutreachProperties props;
    private final LeadService leads;
    private final EmailValidator validator;
    private final CompanyScraper scraper;
    private final SafetyGates gates;
    private final OllamaClient ollama;
    private final GmailSender sender;
    private final BounceTracker bounce;
    private final DailyLimitService limits;
    private final SentEmailRepository sentRepo;
    private final OutreachLogService logService;

    public void runProfile(OutreachProfileEntity profile) {
        if (!profile.isEnabled() || !OutreachTime.isWorkingDay()) {
            return;
        }
        OutreachAccountStateEntity state = bounce.state(profile.getKey());
        limits.resetIfNewDay(state);

        log.info("🚀 [{}] Starting email send run...", profile.getKey());
        int cap = limits.effectiveCap(profile, state);
        if (cap <= 0) {
            log.info("🛑 [{}] [WARMUP] Daily cap is 0 today (warm-up/bounce throttle) — not sending.",
                    profile.getKey());
            return;
        }
        // Reserve the follow-up share of the cap for follow-ups; initial (new-lead) sends take the
        // rest (e.g. 70%). Anything initial can't fill (no enriched leads) is left for follow-ups.
        int initialCap = props.isFollowupsEnabled()
                ? (int) Math.ceil(cap * (1.0 - props.getFollowupRatio())) : cap;
        log.info("📊 [{}] [COUNTER] Daily cap={} (initial={}, follow-ups={}), already sent today={}.",
                profile.getKey(), cap, initialCap, cap - initialCap, state.getSentToday());

        int consecutiveErrors = 0;
        while (state.getSentToday() - state.getFollowupsSentToday() < initialCap
                && state.getSentToday() < cap
                && OutreachTime.isWorkingHours(props.getWorkStartHour(), props.getWorkEndHour())) {
            LeadEntity lead = null;
            try {
                log.info("📋 [{}] [LEAD] Reserving next enriched lead...", profile.getKey());
                lead = leads.reserveForSending();
                if (lead == null) {
                    log.info("⏹️ [{}] [LEAD] No enriched leads left to contact (extraction fills the pool).",
                            profile.getKey());
                    break;
                }
                log.info("✅ [{}] [LEAD] Reserved: company={} | industry={} | country={}", profile.getKey(),
                        lead.getName(), nz(lead.getIndustry()), nz(lead.getCountryCode()));
                boolean sent = processLead(profile, state, lead);
                if (sent) {
                    lead = null; // consumed as USED
                    consecutiveErrors = 0;
                    state.setSentToday(state.getSentToday() + 1);
                    bounce.save(state);
                    int wait = ThreadLocalRandom.current().nextInt(
                            props.getMinDelaySeconds(), props.getMaxDelaySeconds() + 1);
                    log.info("📊 [{}] [COUNTER] Sent today: {}/{}. ⏳ [DELAY] Waiting {}s before next email...",
                            profile.getKey(), state.getSentToday(), cap, wait);
                    sleep(wait);
                } else {
                    // CONTENT rejection (bad email / no company info / gate / model shouldSend=false):
                    // leave the lead USED (already set by reserveForSending) so we DON'T re-reserve the
                    // same top lead in a tight loop — move on to the next one. Only transient EXCEPTIONS
                    // (Ollama/SMTP down, handled in the catch below) revert the lead for a later retry.
                    log.info("⏭️ [{}] [LEAD] {} left out of the send pool (rejected, not retried).",
                            profile.getKey(), lead.getName());
                    lead = null;
                    sleepQuiet(1);
                }
            } catch (InterruptedException ie) {
                if (lead != null) leads.revertToEnriched(lead.getId());
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // ALWAYS return the claimed lead to the pool so it isn't consumed without a send.
                if (lead != null) leads.revertToEnriched(lead.getId());
                log.warn("[{}] lead error: {}", profile.getKey(), e.getMessage());
                if (++consecutiveErrors >= 5) {
                    log.warn("[{}] too many consecutive errors (Ollama/SMTP down?) — stopping this run.",
                            profile.getKey());
                    break;
                }
                sleepQuiet(5);
            }
        }
    }

    /**
     * @return true if an email was actually sent. The recipient email was already found by the
     * extraction pipeline and stored on the lead — sending no longer calls Apollo.
     */
    private boolean processLead(OutreachProfileEntity profile, OutreachAccountStateEntity state, LeadEntity lead)
            throws Exception {
        if (lead.getEmails() == null || lead.getEmails().isEmpty()) {
            log.info("⏭️ [{}] [LEAD] Skip {} — no email stored on lead.", profile.getKey(), lead.getName());
            return false;
        }
        EmailValidator.Result v = validator.validate(lead.getEmails().get(0));
        if (!v.valid()) {
            log.info("⏭️ [{}] [VALIDATE] Skip {} — email {} failed validation ({}).", profile.getKey(),
                    lead.getName(), lead.getEmails().get(0), v.reason());
            return false;
        }
        String email = v.email();
        log.info("✅ [{}] [VALIDATE] Email {} passed all checks.", profile.getKey(), email);

        // Prefer the website context saved at extraction; fall back to a live scrape.
        List<String> companyInfo;
        if (lead.getWebsiteInfo() != null && !lead.getWebsiteInfo().isBlank()) {
            companyInfo = List.of(lead.getWebsiteInfo());
            log.info("🌐 [{}] [SCRAPE] Using stored website info for {} ({} chars).", profile.getKey(),
                    lead.getName(), lead.getWebsiteInfo().length());
        } else {
            log.info("🌐 [{}] [SCRAPE] Extracting company info for {} ({})...", profile.getKey(),
                    lead.getName(), nz(lead.getWebsite()));
            companyInfo = scraper.scrape(lead.getWebsite());
            log.info("✅ [{}] [SCRAPE] Company info snippets: {}", profile.getKey(), companyInfo.size());
        }
        SafetyGates.Gate quality = gates.validateLeadQuality(lead.getName(), email, companyInfo);
        if (!quality.valid()) {
            log.info("⏭️ [{}] [QUALITY] Skip {} — {}.", profile.getKey(), lead.getName(), quality.reason());
            return false;
        }
        log.info("✅ [{}] [QUALITY] Lead {} passed quality check.", profile.getKey(), lead.getName());

        String confidence = gates.computeCompanyNameConfidence(lead.getName(),
                companyInfo.stream().filter(s -> s != null && s.trim().length() > 20).limit(4).toList());
        log.info("🔍 [{}] [AI] Company-name confidence for \"{}\": {}", profile.getKey(),
                lead.getName(), confidence);

        String firstName = firstNameFromEmail(email);
        log.info("🤖 [{}] [AI] Generating email for {} (recipient: {}, {})...", profile.getKey(),
                lead.getName(), firstName == null ? "no name" : firstName, props.getOllamaModel());
        OllamaClient.GeneratedEmail gen = ollama.generate(lead.getName(), lead.getIndustry(),
                lead.getCountryCode(), companyInfo, firstName, null, email, confidence);
        log.info("✅ [{}] [AI] Parsed — shouldSend={} | language={} | subject={}", profile.getKey(),
                gen.shouldSend(), nz(gen.language()), nz(gen.subject()));
        if (!gen.shouldSend()) {
            log.info("⏭️ [{}] [AI] Skip {} — model shouldSend=false ({}).", profile.getKey(),
                    lead.getName(), gen.skipReason());
            return false;
        }
        SafetyGates.Gate safe = gates.validateGeneratedEmail(gen.subject(), gen.emailBody(), lead.getName());
        if (!safe.valid()) {
            log.info("⏭️ [{}] [VALIDATE] Skip {} — generated email failed safety gate ({}).",
                    profile.getKey(), lead.getName(), safe.reason());
            return false;
        }
        log.info("✅ [{}] [VALIDATE] Generated email passed safety check.", profile.getKey());

        log.info("✉️ [{}] [GMAIL] Sending... → From: {} ({}) | To: {} | Subject: {} | Body: {}...",
                profile.getKey(), profile.getFromEmail(), nz(profile.getFromName()), email,
                gen.subject(), preview(gen.emailBody()));
        GmailSender.SendResult result = sender.send(profile, email, gen.subject(), gen.emailBody(), null);

        leads.markContacted(lead.getId());
        record(profile, lead, null, email, gen, result.messageId(), 1, null);
        log.info("✅ [{}] [GMAIL] Email sent to {} ({}) — msgId={}", profile.getKey(),
                email, lead.getName(), result.messageId());
        // Dedicated, analyzable email-sending log ({logDir}/{profile}/sent-{day}.txt).
        logService.recordToStream(profile.getKey(), "sent", String.format(
                "SENT step=1 to=%s company=\"%s\" subject=\"%s\" msgId=%s",
                email, safe(lead.getName()), safe(gen.subject()), result.messageId()));
        return true;
    }

    private void record(OutreachProfileEntity profile, LeadEntity lead, ApolloClient.Contact contact,
                        String email, OllamaClient.GeneratedEmail gen, String messageId, int step, String inReplyTo) {
        SentEmailEntity s = new SentEmailEntity();
        s.setProfileKey(profile.getKey());
        s.setFromEmail(profile.getFromEmail());
        s.setCompanyId(lead.getId());
        s.setCompanyName(lead.getName());
        s.setToEmail(email);
        s.setToName(contact == null ? null : contact.name());
        s.setWebsite(lead.getWebsite());
        s.setCity(lead.getCity());
        s.setSubject(gen.subject());
        s.setBody(gen.emailBody());
        s.setSequenceStep(step);
        s.setThreadKey(profile.getKey() + ":" + lead.getId());
        s.setMessageId(messageId);
        s.setInReplyTo(inReplyTo);
        s.setStatus(SendStatus.SENT);
        s.setSentAt(Instant.now());
        s.setLastSentAt(Instant.now());
        sentRepo.save(s);
    }

    /** One-line-safe: strip newlines/quotes so a value can't break the send-log line format. */
    private String safe(String v) {
        return v == null ? "" : v.replaceAll("[\\r\\n\\t]+", " ").replace("\"", "'").trim();
    }

    /** Null/blank → "N/A" for readable log lines (matches the old script). */
    private String nz(String v) {
        return v == null || v.isBlank() ? "N/A" : v;
    }

    /** Role/functional mailbox prefixes we must NEVER turn into a person's name. */
    private static final java.util.Set<String> ROLE_PREFIXES = java.util.Set.of(
            "info", "sales", "hello", "hi", "help", "support", "admin", "office", "team", "hr",
            "contact", "press", "marketing", "jobs", "careers", "career", "noreply", "no-reply",
            "mail", "email", "enquiries", "enquiry", "inquiries", "service", "services", "billing",
            "accounts", "account", "finance", "legal", "privacy", "security", "webmaster",
            "postmaster", "newsletter", "media", "pr", "partners", "partnership", "business", "dev",
            "developers", "it", "tech", "general", "company", "orders", "order", "booking", "reception");

    /**
     * Best-effort first name from the email local-part, used to personalise the greeting
     * ("Hello, Regis,"). CONSERVATIVE on purpose: only when the local-part is a clear
     * {@code firstname.lastname}-style pattern (a separator + an alphabetic first segment ≥2 chars)
     * and not a role mailbox. Otherwise returns null and the AI writes a plain "Hello,".
     */
    private String firstNameFromEmail(String email) {
        if (email == null) {
            return null;
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return null;
        }
        String local = email.substring(0, at).toLowerCase().trim();
        String[] parts = local.split("[._+\\-]+");
        if (parts.length < 2) {
            return null; // single token (jsmith / anormann / info) → too ambiguous, stay generic
        }
        String first = parts[0].replaceAll("[^a-z]", "");
        if (first.length() < 2 || first.length() > 20 || ROLE_PREFIXES.contains(first)) {
            return null;
        }
        return Character.toUpperCase(first.charAt(0)) + first.substring(1);
    }

    /** First ~100 chars of a body on one line, for the "Body: …" log preview. */
    private String preview(String body) {
        if (body == null) {
            return "";
        }
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() > 100 ? flat.substring(0, 100) : flat;
    }

    private void sleep(int seconds) throws InterruptedException {
        Thread.sleep(seconds * 1000L);
    }

    private void sleepQuiet(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
