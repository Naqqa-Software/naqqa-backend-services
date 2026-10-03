package com.naqqa.analytics.query;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public class QueryCache {

    private final long ttlMs;
    private final int maxEntries;
    private final Clock clock;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public QueryCache(long ttlMs, int maxEntries, Clock clock) {
        this.ttlMs = ttlMs;
        this.maxEntries = maxEntries;
        this.clock = clock;
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, Supplier<T> loader) {
        if (ttlMs <= 0) {
            return loader.get();
        }
        long now = clock.millis();
        Entry e = entries.get(key);
        if (e != null && e.expiresAt > now) {
            return (T) e.value;
        }
        T value = loader.get();
        if (entries.size() >= maxEntries) {
            entries.entrySet().removeIf(x -> x.getValue().expiresAt <= now);
            if (entries.size() >= maxEntries) {
                entries.clear();
            }
        }
        entries.put(key, new Entry(value, now + ttlMs));
        return value;
    }

    public void clear() {
        entries.clear();
    }

    private record Entry(Object value, long expiresAt) {
    }
}
