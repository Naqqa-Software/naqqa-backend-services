package com.naqqa.analytics.banners.store;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class MemoryBannerCounters implements BannerCounters {

    private record Entry(AtomicInteger count, long expiresAt) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int maxEntries;

    public MemoryBannerCounters(Clock clock, int maxEntries) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.maxEntries = Math.max(1000, maxEntries);
    }

    public MemoryBannerCounters() {
        this(Clock.systemUTC(), 200_000);
    }

    @Override
    public int servedToday(String vid, String campaignId, LocalDate day) {
        if (vid == null) {
            return 0;
        }
        Entry e = live(freqKey(vid, campaignId, day));
        return e == null ? 0 : e.count().get();
    }

    @Override
    public void recordServe(String vid, String campaignId, LocalDate day) {
        if (vid == null) {
            return;
        }
        long now = clock.millis();
        increment(freqKey(vid, campaignId, day), now + Duration.ofHours(48).toMillis());
        entries.put(seenKey(vid, campaignId), new Entry(new AtomicInteger(1), now + Duration.ofHours(24).toMillis()));
    }

    @Override
    public boolean seen(String vid, String campaignId) {
        return vid != null && live(seenKey(vid, campaignId)) != null;
    }

    @Override
    public boolean firstClick(String key, Duration window) {
        long now = clock.millis();
        Entry fresh = new Entry(new AtomicInteger(1), now + window.toMillis());
        Entry existing = entries.compute("c:" + key, (k, current) -> current == null || current.expiresAt() <= now ? fresh : current);
        prune(now);
        return existing == fresh;
    }

    private void increment(String key, long expiresAt) {
        long now = clock.millis();
        entries.compute(key, (k, current) -> {
            if (current == null || current.expiresAt() <= now) {
                return new Entry(new AtomicInteger(1), expiresAt);
            }
            current.count().incrementAndGet();
            return current;
        });
        prune(now);
    }

    private Entry live(String key) {
        Entry e = entries.get(key);
        if (e == null) {
            return null;
        }
        if (e.expiresAt() <= clock.millis()) {
            entries.remove(key, e);
            return null;
        }
        return e;
    }

    private void prune(long now) {
        if (entries.size() <= maxEntries) {
            return;
        }
        Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().expiresAt() <= now) {
                it.remove();
            }
        }
        if (entries.size() > maxEntries) {
            entries.clear();
        }
    }

    static String freqKey(String vid, String campaignId, LocalDate day) {
        return "f:" + day + ":" + campaignId + ":" + vid;
    }

    static String seenKey(String vid, String campaignId) {
        return "s:" + campaignId + ":" + vid;
    }
}
