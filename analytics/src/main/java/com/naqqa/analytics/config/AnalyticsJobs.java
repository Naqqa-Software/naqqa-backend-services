package com.naqqa.analytics.config;

import com.naqqa.analytics.collect.EventWriter;
import com.naqqa.analytics.reports.ScheduledReportService;
import com.naqqa.analytics.rollup.RollupJob;
import com.naqqa.analytics.web.RealtimeHub;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class AnalyticsJobs implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    private final NaqqaAnalyticsProperties properties;
    private final ThreadPoolTaskScheduler scheduler;
    private final EventWriter writer;
    private final RollupJob rollups;
    private final ScheduledReportService reports;
    private final RealtimeHub hub;
    private final AnalyticsMongoIndexes indexes;
    private final List<ScheduledFuture<?>> futures = new ArrayList<>();
    private final AtomicBoolean started = new AtomicBoolean(false);

    public AnalyticsJobs(NaqqaAnalyticsProperties properties, ThreadPoolTaskScheduler scheduler, EventWriter writer, RollupJob rollups,
                         ScheduledReportService reports, RealtimeHub hub, AnalyticsMongoIndexes indexes) {
        this.properties = properties;
        this.scheduler = scheduler;
        this.writer = writer;
        this.rollups = rollups;
        this.reports = reports;
        this.hub = hub;
        this.indexes = indexes;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        if (indexes != null && properties.getCollections().isCreateIndexes()) {
            scheduler.execute(safe(indexes::ensure));
        }
        long flush = Math.max(200L, properties.getWriter().getFlushIntervalMs());
        futures.add(scheduler.scheduleWithFixedDelay(safe(writer::flush), Instant.now().plusMillis(flush), Duration.ofMillis(flush)));
        long push = Math.max(1_000L, properties.getRealtime().getPushIntervalMs());
        futures.add(scheduler.scheduleWithFixedDelay(safe(hub::push), Instant.now().plusMillis(push), Duration.ofMillis(push)));
        ZoneId zone = ZoneId.of(properties.getTimezone());
        if (properties.getRollup().isEnabled()) {
            long every = Math.max(60_000L, properties.getRollup().getTodayIntervalMs());
            futures.add(scheduler.scheduleWithFixedDelay(safe(rollups::today), Instant.now().plusSeconds(120), Duration.ofMillis(every)));
            if (properties.getRollupCron() != null && !properties.getRollupCron().isBlank()) {
                futures.add(scheduler.schedule(safe(rollups::reconcileYesterday), new CronTrigger(properties.getRollupCron(), zone)));
            }
        }
        if (properties.getReports().isEnabled() && properties.getReports().getCron() != null && !properties.getReports().getCron().isBlank()) {
            futures.add(scheduler.schedule(safe(reports::runDue), new CronTrigger(properties.getReports().getCron(), zone)));
        }
    }

    private static Runnable safe(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.warn("[analytics] scheduled task failed: {}", e.getMessage());
            }
        };
    }

    @Override
    public void destroy() {
        for (ScheduledFuture<?> f : futures) {
            f.cancel(false);
        }
        futures.clear();
        try {
            writer.flush();
        } catch (RuntimeException e) {
            log.warn("[analytics] final flush failed: {}", e.getMessage());
        }
    }
}
