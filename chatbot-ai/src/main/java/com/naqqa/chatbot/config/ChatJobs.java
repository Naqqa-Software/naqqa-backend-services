package com.naqqa.chatbot.config;

import com.naqqa.chatbot.ai.knowledge.KnowledgeService;
import com.naqqa.chatbot.repository.ChatMongoIndexes;
import com.naqqa.chatbot.service.ChatMaintenanceJobs;
import com.naqqa.chatbot.service.ChatSettingsService;
import com.naqqa.chatbot.sse.ChatSseHub;
import lombok.extern.slf4j.Slf4j;
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
public class ChatJobs implements ApplicationListener<ApplicationReadyEvent> {

    private final NaqqaChatbotProperties properties;
    private final ThreadPoolTaskScheduler scheduler;
    private final ChatSseHub hub;
    private final ChatMaintenanceJobs maintenance;
    private final KnowledgeService knowledge;
    private final ChatSettingsService settings;
    private final ChatMongoIndexes indexes;
    private final List<Runnable> extraNightly;
    private final List<ScheduledFuture<?>> futures = new ArrayList<>();
    private final AtomicBoolean started = new AtomicBoolean(false);

    public ChatJobs(NaqqaChatbotProperties properties, ThreadPoolTaskScheduler scheduler, ChatSseHub hub,
                    ChatMaintenanceJobs maintenance, KnowledgeService knowledge, ChatSettingsService settings,
                    ChatMongoIndexes indexes, List<Runnable> extraNightly) {
        this.properties = properties;
        this.scheduler = scheduler;
        this.hub = hub;
        this.maintenance = maintenance;
        this.knowledge = knowledge;
        this.settings = settings;
        this.indexes = indexes;
        this.extraNightly = extraNightly == null ? List.of() : List.copyOf(extraNightly);
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        if (indexes != null && properties.getCollections().isCreateIndexes()) {
            try {
                indexes.ensure();
            } catch (RuntimeException e) {
                log.warn("[chatbot] index creation failed: {}", e.getMessage());
            }
        }
        try {
            settings.onReady();
        } catch (RuntimeException e) {
            log.warn("[chatbot] settings initialisation failed: {}", e.getMessage());
        }
        if (knowledge != null && properties.getKnowledge().isReindexOnStartup()) {
            knowledge.initialIndex();
        }
        if (!properties.getJobs().isEnabled()) {
            return;
        }
        NaqqaChatbotProperties.Jobs jobs = properties.getJobs();
        long keepAlive = Math.max(1_000L, jobs.getKeepAliveMs());
        futures.add(scheduler.scheduleAtFixedRate(safe(hub::keepAlive), Instant.now().plusMillis(keepAlive), Duration.ofMillis(keepAlive)));
        futures.add(scheduler.scheduleWithFixedDelay(safe(maintenance::closeInactive),
                Instant.now().plusMillis(Math.max(0, jobs.getInactivityInitialDelayMs())),
                Duration.ofMillis(Math.max(10_000L, jobs.getInactivityCheckMs()))));
        if (jobs.getRetentionCron() != null && !jobs.getRetentionCron().isBlank()) {
            futures.add(scheduler.schedule(safe(maintenance::applyRetention), trigger(jobs.getRetentionCron(), jobs.getRetentionZone())));
        }
        NaqqaChatbotProperties.Knowledge k = properties.getKnowledge();
        if (knowledge != null && k.getReindexCron() != null && !k.getReindexCron().isBlank()) {
            futures.add(scheduler.schedule(safe(knowledge::nightly), trigger(k.getReindexCron(), k.getReindexZone())));
        }
        for (Runnable r : extraNightly) {
            futures.add(scheduler.schedule(safe(r), trigger(k.getReindexCron() == null || k.getReindexCron().isBlank()
                    ? "0 30 3 * * *" : k.getReindexCron(), k.getReindexZone())));
        }
    }

    private static CronTrigger trigger(String cron, String zone) {
        if (zone == null || zone.isBlank()) {
            return new CronTrigger(cron);
        }
        return new CronTrigger(cron, ZoneId.of(zone));
    }

    private static Runnable safe(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.warn("[chatbot] scheduled task failed: {}", e.getMessage());
            }
        };
    }

    public void stop() {
        for (ScheduledFuture<?> f : futures) {
            f.cancel(false);
        }
        futures.clear();
    }
}
