package com.naqqa.auth.service.security;


import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Small in-memory fixed-window counters (per instance) for auth side effects such as verification e-mail
 * resends. Only expired windows are evicted; when the map is full of live windows new subjects are refused
 * (fail closed) instead of resetting anyone's counter.
 */

public class AuthAttemptLimiter {

    static final int MAX_KEYS = 50_000;

    private record Window(long start, long length, AtomicInteger count) {
        boolean expired(long now) {
            return now - start >= length;
        }
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    /** Records an attempt; true while the subject is still within {@code limit} attempts per window. */
    public boolean tryAcquire(String purpose, String subject, int limit, long windowMillis) {
        long now = System.currentTimeMillis();
        String key = purpose + "|" + (subject == null ? "" : subject.trim().toLowerCase(Locale.ROOT));
        if (!windows.containsKey(key) && windows.size() >= MAX_KEYS) {
            windows.entrySet().removeIf(e -> e.getValue().expired(now));
            if (windows.size() >= MAX_KEYS) {
                return false;
            }
        }
        Window window = windows.compute(key, (k, existing) ->
                existing == null || existing.expired(now) ? new Window(now, windowMillis, new AtomicInteger()) : existing);
        return window.count().incrementAndGet() <= limit;
    }
}
