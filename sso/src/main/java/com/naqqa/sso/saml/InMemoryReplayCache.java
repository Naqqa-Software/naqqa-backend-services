package com.naqqa.sso.saml;

import com.naqqa.sso.SsoException;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class InMemoryReplayCache implements ReplayCache {
    private final Clock clock;
    private final int maxEntries;
    private final Map<String, Instant> entries = new HashMap<>();

    public InMemoryReplayCache(Clock clock) {
        this(clock, 100_000);
    }

    public InMemoryReplayCache(Clock clock, int maxEntries) {
        this.clock = clock;
        this.maxEntries = maxEntries;
    }

    @Override
    public synchronized boolean markIfAbsent(String key, Instant expiresAt) {
        Instant now = clock.instant();
        Instant existing = entries.get(key);
        if (existing != null && existing.isAfter(now)) {
            return false;
        }
        if (entries.size() >= maxEntries) {
            purge(now);
            if (entries.size() >= maxEntries) {
                throw new SsoException("SAML_REPLAY_CACHE_FULL");
            }
        }
        entries.put(key, expiresAt);
        return true;
    }

    public synchronized int size() {
        purge(clock.instant());
        return entries.size();
    }

    private void purge(Instant now) {
        Iterator<Map.Entry<String, Instant>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            if (!it.next().getValue().isAfter(now)) {
                it.remove();
            }
        }
    }
}
