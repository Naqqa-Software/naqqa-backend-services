package com.naqqa.sso.saml.sp;

import com.naqqa.sso.SsoException;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public final class InMemorySamlRequestStore implements SamlRequestStore {
    private final Clock clock;
    private final int maxEntries;
    private final Map<String, Instant> pending = new HashMap<>();

    public InMemorySamlRequestStore(Clock clock) {
        this(clock, 100_000);
    }

    public InMemorySamlRequestStore(Clock clock, int maxEntries) {
        this.clock = clock;
        this.maxEntries = maxEntries;
    }

    @Override
    public synchronized void save(String requestId, Instant expiresAt) {
        if (pending.size() >= maxEntries) {
            Instant now = clock.instant();
            pending.values().removeIf(e -> !e.isAfter(now));
            if (pending.size() >= maxEntries) {
                throw new SsoException("SAML_REQUEST_STORE_FULL");
            }
        }
        pending.put(requestId, expiresAt);
    }

    @Override
    public synchronized boolean consume(String requestId) {
        Instant expires = pending.remove(requestId);
        return expires != null && expires.isAfter(clock.instant());
    }
}
