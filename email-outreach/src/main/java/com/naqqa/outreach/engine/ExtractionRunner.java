package com.naqqa.outreach.engine;

import com.naqqa.outreach.config.OutreachProperties;
import com.naqqa.outreach.entity.LeadEntity;
import com.naqqa.outreach.logging.OutreachLogAppender;
import com.naqqa.outreach.service.ApolloClient;
import com.naqqa.outreach.service.CompanyScraper;
import com.naqqa.outreach.service.EmailValidator;
import com.naqqa.outreach.service.LeadService;
import com.naqqa.outreach.service.OutreachSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Continuously enriches DEFAULT companies with a decision-maker email via Apollo, independently of
 * the daily <em>send</em> cap — it runs up to your Apollo API limits and just keeps filling the
 * enriched pool. The extracted email is mapped onto the company (status DEFAULT → ENRICHED); the
 * daily send loop then drains that pool. Companies Apollo can't resolve become NO_EMAIL so they are
 * not retried forever.
 *
 * <p>Single-threaded by design (Spring's {@code fixedDelay} waits for each tick to finish), so the
 * atomic claim + finalize never overlaps. A stuck ENRICHING lead (crash mid-lookup) is released on
 * the next startup.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExtractionRunner {

    private final OutreachProperties props;
    private final LeadService leads;
    private final ApolloClient apollo;
    private final EmailValidator validator;
    private final CompanyScraper scraper;
    private final OutreachSettingsService settings;

    /** Release leads left mid-extraction by a previous crash/restart. */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        if (!props.isEnabled()) {
            return;
        }
        long released = leads.resetStaleEnriching();
        if (released > 0) {
            log.info("[extraction] released {} stale ENRICHING leads back to the pool.", released);
        }
    }

    @Scheduled(fixedDelayString = "${naqqa.outreach.extraction-tick-ms:30000}", initialDelay = 15000)
    public void tick() {
        // props.* = deploy-time switches; settings.extractionActive = the admin-panel runtime toggle.
        if (!props.isEnabled() || !props.isExtractionEnabled() || !settings.isExtractionActive()) {
            return;
        }
        MDC.put(OutreachLogAppender.MDC_KEY, "leadgen");
        try {
            int done = 0;
            for (int i = 0; i < props.getExtractionBatchSize(); i++) {
                LeadEntity lead = leads.reserveForExtraction();
                if (lead == null) {
                    break; // pool of DEFAULT companies is drained
                }
                try {
                    extract(lead);
                    done++;
                } catch (Exception e) {
                    log.warn("[extraction] {} failed: {}", lead.getWebsite(), e.getMessage());
                    leads.markNoEmail(lead.getId());
                }
                sleepQuiet(props.getExtractionDelayMs());
            }
            if (done > 0) {
                log.info("[extraction] enriched {} companies this tick ({} ready to send).",
                        done, leads.enrichedCount());
            }
        } finally {
            MDC.remove(OutreachLogAppender.MDC_KEY);
        }
    }

    /**
     * Back-fill website info for companies that already have an email but no scraped info yet
     * (e.g. the seeded/historical contacts). Runs alongside extraction, paced the same way.
     */
    @Scheduled(fixedDelayString = "${naqqa.outreach.scrape-tick-ms:60000}", initialDelay = 45000)
    public void scrapeInfoTick() {
        if (!props.isEnabled() || !props.isExtractionEnabled() || !settings.isExtractionActive()) {
            return;
        }
        MDC.put(OutreachLogAppender.MDC_KEY, "leadgen");
        try {
            int done = 0;
            for (int i = 0; i < props.getExtractionBatchSize(); i++) {
                LeadEntity lead = leads.reserveForScrape();
                if (lead == null) {
                    break; // every emailed company already has (or was marked for) info
                }
                try {
                    List<String> info = scraper.scrape(lead.getWebsite());
                    String joined = (info == null || info.isEmpty()) ? "" : String.join("\n", info);
                    leads.markScraped(lead.getId(), joined.length() > 4000 ? joined.substring(0, 4000) : joined);
                    done++;
                } catch (Exception e) {
                    leads.markScraped(lead.getId(), ""); // mark so it isn't retried forever
                }
                sleepQuiet(props.getExtractionDelayMs());
            }
            if (done > 0) {
                log.info("[scrape] back-filled website info for {} companies this tick.", done);
            }
        } finally {
            MDC.remove(OutreachLogAppender.MDC_KEY);
        }
    }

    private void extract(LeadEntity lead) {
        ApolloClient.Contact contact = apollo.findEmail(lead.getWebsite());
        if (contact == null || contact.email() == null) {
            leads.markNoEmail(lead.getId());
            return;
        }
        EmailValidator.Result v = validator.validate(contact.email());
        if (!v.valid()) {
            leads.markNoEmail(lead.getId());
            return;
        }
        // Persist the email on the company immediately — saved in the DB even if it is never sent.
        leads.markEnriched(lead.getId(), v.email());
        // Scrape + persist company context from the website (shown in the drawer, reused by the AI).
        try {
            List<String> info = scraper.scrape(lead.getWebsite());
            if (info != null && !info.isEmpty()) {
                String joined = String.join("\n", info);
                leads.saveWebsiteInfo(lead.getId(), joined.length() > 4000 ? joined.substring(0, 4000) : joined);
            }
        } catch (Exception e) {
            log.debug("[extraction] scrape failed for {}: {}", lead.getWebsite(), e.getMessage());
        }
        log.info("[extraction] {} ({}) -> {} saved", lead.getName(), lead.getSize(), v.email());
    }

    private void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
