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

        int cap = limits.effectiveCap(profile, state);
        if (cap <= 0) {
            log.info("[{}] not sending today (warm-up/bounce cap = 0).", profile.getKey());
            return;
        }
        // Reserve the follow-up share of the cap for follow-ups; initial (new-lead) sends take the
        // rest (e.g. 70%). Anything initial can't fill (no enriched leads) is left for follow-ups.
        int initialCap = props.isFollowupsEnabled()
                ? (int) Math.ceil(cap * (1.0 - props.getFollowupRatio())) : cap;
        log.info("[{}] initial-send run: cap={} initialCap={} sentToday={}", profile.getKey(),
                cap, initialCap, state.getSentToday());

        int consecutiveErrors = 0;
        while (state.getSentToday() - state.getFollowupsSentToday() < initialCap
                && state.getSentToday() < cap
                && OutreachTime.isWorkingHours(props.getWorkStartHour(), props.getWorkEndHour())) {
            LeadEntity lead = null;
            try {
                lead = leads.reserveForSending();
                if (lead == null) {
                    log.info("[{}] no enriched leads left to contact (extraction fills the pool).",
                            profile.getKey());
                    break;
                }
                boolean sent = processLead(profile, state, lead);
                if (sent) {
                    lead = null; // consumed as USED
                    consecutiveErrors = 0;
                    state.setSentToday(state.getSentToday() + 1);
                    bounce.save(state);
                    sleep(ThreadLocalRandom.current().nextInt(
                            props.getMinDelaySeconds(), props.getMaxDelaySeconds() + 1));
                } else {
                    // gate/generation/send failed — return it to the enriched pool for a later retry.
                    // Short back-off so a lead the gates keep rejecting can't hot-loop the CPU silently.
                    leads.revertToEnriched(lead.getId());
                    lead = null;
                    sleepQuiet(3);
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
            log.info("[{}] skip {} — no email stored on lead.", profile.getKey(), lead.getName());
            return false;
        }
        EmailValidator.Result v = validator.validate(lead.getEmails().get(0));
        if (!v.valid()) {
            log.info("[{}] skip {} — email failed validation: {}", profile.getKey(),
                    lead.getName(), lead.getEmails().get(0));
            return false;
        }
        String email = v.email();
        log.info("[{}] processing {} <{}>", profile.getKey(), lead.getName(), email);

        // Prefer the website context saved at extraction; fall back to a live scrape.
        List<String> companyInfo;
        if (lead.getWebsiteInfo() != null && !lead.getWebsiteInfo().isBlank()) {
            companyInfo = List.of(lead.getWebsiteInfo());
        } else {
            log.info("[{}] scraping website {} for {}", profile.getKey(), lead.getWebsite(), lead.getName());
            companyInfo = scraper.scrape(lead.getWebsite());
        }
        SafetyGates.Gate quality = gates.validateLeadQuality(lead.getName(), email, companyInfo);
        if (!quality.valid()) {
            log.info("[{}] skip {} — quality gate: {}", profile.getKey(), lead.getName(), quality.reason());
            return false;
        }

        String confidence = gates.computeCompanyNameConfidence(lead.getName(),
                companyInfo.stream().filter(s -> s != null && s.trim().length() > 20).limit(4).toList());

        log.info("[{}] generating email for {} (Ollama)...", profile.getKey(), lead.getName());
        OllamaClient.GeneratedEmail gen = ollama.generate(lead.getName(), lead.getIndustry(),
                lead.getCountryCode(), companyInfo, null, null, email, confidence);
        log.info("[{}] generated for {} (shouldSend={}, lang={})", profile.getKey(),
                lead.getName(), gen.shouldSend(), gen.language());
        if (!gen.shouldSend()) {
            log.info("[{}] skip {} — model returned shouldSend=false ({}).", profile.getKey(),
                    lead.getName(), gen.skipReason());
            return false;
        }
        SafetyGates.Gate safe = gates.validateGeneratedEmail(gen.subject(), gen.emailBody(), lead.getName());
        if (!safe.valid()) {
            log.info("[{}] generated email failed gate ({}) for {}", profile.getKey(), safe.reason(), email);
            return false;
        }

        GmailSender.SendResult result = sender.send(profile, email, gen.subject(), gen.emailBody(), null);

        leads.markContacted(lead.getId());
        record(profile, lead, null, email, gen, result.messageId(), 1, null);
        log.info("[{}] sent step 1 -> {} ({})", profile.getKey(), email, lead.getName());
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
