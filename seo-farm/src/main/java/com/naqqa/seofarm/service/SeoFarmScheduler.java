package com.naqqa.seofarm.service;

import com.naqqa.seofarm.config.SeoFarmProperties;
import com.naqqa.seofarm.model.SeoSite;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * Daily blog-generation catch-up. Runs at the {@code schedule-cron} (default 21:00 / 9 PM) AND on
 * app startup: for each ACTIVE site it checks whether today's blog was already produced and, if not,
 * generates one. Idempotent — a restart after a successful run won't double-generate. Disabled via
 * {@code schedule-enabled} (the manual "Generate now" button still works). Needs host @EnableScheduling.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SeoFarmScheduler {

    private final SeoFarmProperties props;
    private final SeoPagesService pagesService;
    private final BlogGenerationService generation;
    private final SeoBlogService blogs;

    /** 9 PM daily (configurable). */
    @Scheduled(cron = "${naqqa.seofarm.schedule-cron:0 0 21 * * *}")
    public void dailyRun() {
        catchUp("cron");
    }

    /**
     * On boot, generate any active site that hasn't produced today's blog yet. Ordered FIRST among startup
     * listeners (well ahead of the company-sources CDX harvester at {@code @Order(20)}), and since this runs
     * synchronously the day's blog is fully generated before the long harvester sweep starts competing.
     */
    @Order(0)
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        catchUp("startup");
    }

    private void catchUp(String label) {
        if (!props.isScheduleEnabled()) {
            return;
        }
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        DayOfWeek dow = today.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            log.info("[seofarm] {} catch-up: {} is a weekend — skipping blog generation.", label, dow);
            return;
        }
        // Timezone-independent "max 1 per day" guard: skip a site that produced a blog within the cooldown
        // window. Survives restarts (the check is against the DB) and avoids the UTC-vs-local midnight edge
        // that previously let a late-evening blog + a next-morning blog both land on the operator's "today".
        Instant cooldownStart = Instant.now().minus(Math.max(1, props.getMinHoursBetweenBlogs()), ChronoUnit.HOURS);
        for (SeoSite site : pagesService.sites()) { // active sites only
            if (blogs.hasBlogSince(site.fromSite(), cooldownStart)) {
                log.info("[seofarm] {} catch-up: {} already has a blog within the last {}h — skipping.",
                        label, site.id(), props.getMinHoursBetweenBlogs());
                continue;
            }
            // Exactly ONE blog per site per run (and per cooldown window) — never double up.
            var result = generation.generateForSite(site, label);
            log.info("[seofarm] {} generation for {}: {} ({})", label, site.id(), result.status(),
                    "PUBLISHED".equals(result.status()) ? result.slug() : result.error());
        }
    }
}
