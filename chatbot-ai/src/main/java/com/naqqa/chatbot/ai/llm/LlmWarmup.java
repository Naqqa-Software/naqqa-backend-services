package com.naqqa.chatbot.ai.llm;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
public class LlmWarmup implements AutoCloseable {

    private final LlmProvider provider;
    private final ScheduledExecutorService scheduler;
    private final long intervalMs;
    private final boolean ownsScheduler;

    public LlmWarmup(LlmProvider provider, long intervalMs) {
        this(provider, intervalMs, Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "naqqa-chatbot-llm-warmup");
            t.setDaemon(true);
            return t;
        }), true);
    }

    public LlmWarmup(LlmProvider provider, long intervalMs, ScheduledExecutorService scheduler) {
        this(provider, intervalMs, scheduler, false);
    }

    private LlmWarmup(LlmProvider provider, long intervalMs, ScheduledExecutorService scheduler, boolean ownsScheduler) {
        this.provider = provider;
        this.intervalMs = intervalMs;
        this.scheduler = scheduler;
        this.ownsScheduler = ownsScheduler;
    }

    public boolean start() {
        if (provider == null || intervalMs <= 0 || !enabled()) {
            if (ownsScheduler) {
                scheduler.shutdown();
            }
            return false;
        }
        scheduler.schedule(this::run, 0, TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(this::run, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        return true;
    }

    void run() {
        try {
            provider.warmUp();
        } catch (RuntimeException e) {
            log.debug("[chatbot] llm warm-up failed: {}", e.getMessage());
        }
    }

    private boolean enabled() {
        try {
            return provider.isEnabled();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public void close() {
        if (ownsScheduler) {
            scheduler.shutdownNow();
        }
    }
}
