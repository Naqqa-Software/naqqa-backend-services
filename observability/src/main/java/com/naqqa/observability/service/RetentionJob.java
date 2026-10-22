package com.naqqa.observability.service;

import com.naqqa.observability.spi.ObsConfigStore;
import com.naqqa.observability.spi.TelemetryStore;
import com.naqqa.observability.telemetry.ObsProjectConfig;
import com.naqqa.observability.telemetry.ObsProjectConfig.Retention;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class RetentionJob implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);
    static final long DAY_MS = 24L * 3600 * 1000;

    private final TelemetryStore store;
    private final ObsConfigStore configs;
    private final Clock clock;
    private final ScheduledExecutorService executor;

    public RetentionJob(TelemetryStore store, ObsConfigStore configs, Clock clock, long intervalMinutes) {
        this.store = store;
        this.configs = configs;
        this.clock = clock;
        if (intervalMinutes > 0) {
            this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "naqqa-observability-retention");
                t.setDaemon(true);
                return t;
            });
            this.executor.scheduleWithFixedDelay(this::runSafely, intervalMinutes, intervalMinutes, TimeUnit.MINUTES);
        } else {
            this.executor = null;
        }
    }

    void runSafely() {
        try {
            run();
        } catch (RuntimeException e) {
            log.warn("observability retention sweep failed: {}", e.getMessage());
        }
    }

    public int run() {
        long now = clock.millis();
        int projects = 0;
        for (ObsProjectConfig config : configs.all()) {
            Retention r = config.retention();
            store.purge(config.projectId(), now - r.logsDays() * DAY_MS, now - r.metricsDays() * DAY_MS,
                    now - r.tracesDays() * DAY_MS, now - r.issuesDays() * DAY_MS);
            projects++;
        }
        return projects;
    }

    @Override
    public void close() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
