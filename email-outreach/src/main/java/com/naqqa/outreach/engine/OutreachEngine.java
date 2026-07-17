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

        while (state.getSentToday() - state.getFollowupsSentToday() < initialCap
                && state.getSentToday() < cap
                && OutreachTime.isWorkingHours(props.getWorkStartHour(), props.getWorkEndHour())) {
            try {
                LeadEntity lead = leads.reserveForSending();
                if (lead == null) {
                    log.info("[{}] no enriched leads left to contact (extraction fills the pool).",
                            profile.getKey());
                    break;
                }
                boolean sent = processLead(profile, state, lead);
                if (!sent) {
                    // gate/generation/send failed — return it to the enriched pool for a later retry.
                    leads.revertToEnriched(lead.getId());
                }
                if (sent) {
                    state.setSentToday(state.getSentToday() + 1);
                    bounce.save(state);
                    sleep(ThreadLocalRandom.current().nextInt(
                            props.getMinDelaySeconds(), props.getMaxDelaySeconds() + 1));
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("[{}] lead error: {}", profile.getKey(), e.getMessage());
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
            return false;
        }
        EmailValidator.Result v = validator.validate(lead.getEmails().get(0));
        if (!v.valid()) {
            return false;
        }
        String email = v.email();

        // Prefer the website context saved at extraction; fall back to a live scrape.
        List<String> companyInfo = (lead.getWebsiteInfo() != null && !lead.getWebsiteInfo().isBlank())
                ? List.of(lead.getWebsiteInfo())
                : scraper.scrape(lead.getWebsite());
        SafetyGates.Gate quality = gates.validateLeadQuality(lead.getName(), email, companyInfo);
        if (!quality.valid()) {
            return false;
        }

        String confidence = gates.computeCompanyNameConfidence(lead.getName(),
                companyInfo.stream().filter(s -> s != null && s.trim().length() > 20).limit(4).toList());

        OllamaClient.GeneratedEmail gen = ollama.generate(lead.getName(), lead.getIndustry(),
                lead.getCountryCode(), companyInfo, null, null, email, confidence);
        if (!gen.shouldSend()) {
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
        sentRepo.save(s);
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
