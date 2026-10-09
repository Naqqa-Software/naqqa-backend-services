package com.naqqa.analytics.banners.store;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

@Slf4j
public class BannerImpressionBuffer implements AutoCloseable {

    public record Key(String campaignId, String creativeId, String slot) {
    }

    private final BannerRepository repository;
    private final Map<Key, LongAdder> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    private final Object flushLock = new Object();

    public BannerImpressionBuffer(BannerRepository repository, long flushMs) {
        this.repository = repository;
        if (flushMs > 0) {
            this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "naqqa-banner-impressions");
                t.setDaemon(true);
                return t;
            });
            this.scheduler.scheduleWithFixedDelay(this::flushQuietly, flushMs, flushMs, TimeUnit.MILLISECONDS);
        } else {
            this.scheduler = null;
        }
    }

    public static BannerImpressionBuffer direct(BannerRepository repository) {
        return new BannerImpressionBuffer(repository, 0);
    }

    public void record(String campaignId, String creativeId, String slot) {
        if (campaignId == null) {
            return;
        }
        if (scheduler == null) {
            write(new Key(campaignId, creativeId, slot), 1);
            return;
        }
        pending.computeIfAbsent(new Key(campaignId, creativeId, slot), k -> new LongAdder()).increment();
    }

    public long pending(Key key) {
        LongAdder adder = pending.get(key);
        return adder == null ? 0 : adder.sum();
    }

    public int flush() {
        synchronized (flushLock) {
            List<Key> keys = new ArrayList<>(pending.keySet());
            int written = 0;
            for (Key key : keys) {
                LongAdder adder = pending.get(key);
                if (adder == null) {
                    continue;
                }
                long n = adder.sumThenReset();
                if (n <= 0) {
                    continue;
                }
                if (write(key, n)) {
                    written++;
                } else {
                    adder.add(n);
                }
            }
            return written;
        }
    }

    @Override
    public void close() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
        flushQuietly();
    }

    private void flushQuietly() {
        try {
            flush();
        } catch (Exception e) {
            log.warn("Banner impressions could not be flushed: {}", e.getMessage());
        }
    }

    private boolean write(Key key, long n) {
        try {
            repository.incServed(key.campaignId(), key.creativeId(), key.slot(), n);
            return true;
        } catch (Exception e) {
            log.warn("Banner served counter could not be updated: {}", e.getMessage());
            return false;
        }
    }
}
