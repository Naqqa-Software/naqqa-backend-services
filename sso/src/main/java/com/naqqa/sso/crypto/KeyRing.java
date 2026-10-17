package com.naqqa.sso.crypto;

import com.naqqa.sso.SsoException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class KeyRing {
    public record Entry(SigningKey key, Instant notAfter, boolean active) {
        public boolean usableAt(Instant now) {
            return notAfter == null || now.isBefore(notAfter);
        }
    }

    private final Clock clock;
    private volatile Map<String, Entry> entries;

    private KeyRing(Clock clock, Map<String, Entry> entries) {
        if (clock == null) {
            throw new SsoException("keyring.clock_required");
        }
        this.clock = clock;
        this.entries = Collections.unmodifiableMap(entries);
    }

    public static KeyRing of(Clock clock, SigningKey active) {
        requireSigner(active);
        Map<String, Entry> m = new LinkedHashMap<>();
        m.put(active.kid(), new Entry(active, null, true));
        return new KeyRing(clock, m);
    }

    public static KeyRing verifyOnly(Clock clock, Collection<SigningKey> keys) {
        Map<String, Entry> m = new LinkedHashMap<>();
        for (SigningKey k : keys) {
            if (m.put(k.kid(), new Entry(k.publicOnly(), null, false)) != null) {
                throw new SsoException("keyring.duplicate_kid");
            }
        }
        return new KeyRing(clock, m);
    }

    private static void requireSigner(SigningKey key) {
        if (key == null || !key.canSign()) {
            throw new SsoException("keyring.active_requires_private");
        }
    }

    public Clock clock() {
        return clock;
    }

    public SigningKey active() {
        for (Entry e : entries.values()) {
            if (e.active()) {
                return e.key();
            }
        }
        throw new SsoException("keyring.no_active_key");
    }

    public Optional<SigningKey> find(String kid) {
        if (!SigningKey.isValidKid(kid)) {
            return Optional.empty();
        }
        Entry e = entries.get(kid);
        if (e == null || !e.usableAt(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(e.key());
    }

    public List<SigningKey> verificationKeys() {
        Instant now = clock.instant();
        List<SigningKey> out = new ArrayList<>();
        for (Entry e : entries.values()) {
            if (e.usableAt(now)) {
                out.add(e.key().publicOnly());
            }
        }
        return out;
    }

    public List<Entry> entries() {
        return new ArrayList<>(entries.values());
    }

    public synchronized void rotate(SigningKey next, Duration retireAfter) {
        requireSigner(next);
        if (retireAfter == null || retireAfter.isNegative()) {
            throw new SsoException("keyring.retire_invalid");
        }
        if (entries.containsKey(next.kid())) {
            throw new SsoException("keyring.duplicate_kid");
        }
        Instant notAfter = clock.instant().plus(retireAfter);
        Map<String, Entry> m = new LinkedHashMap<>();
        m.put(next.kid(), new Entry(next, null, true));
        for (Entry e : entries.values()) {
            if (e.active()) {
                m.put(e.key().kid(), new Entry(e.key().publicOnly(), notAfter, false));
            } else {
                m.put(e.key().kid(), e);
            }
        }
        entries = Collections.unmodifiableMap(m);
    }

    public synchronized void addVerifyOnly(SigningKey key, Instant notAfter) {
        if (entries.containsKey(key.kid())) {
            throw new SsoException("keyring.duplicate_kid");
        }
        Map<String, Entry> m = new LinkedHashMap<>(entries);
        m.put(key.kid(), new Entry(key.publicOnly(), notAfter, false));
        entries = Collections.unmodifiableMap(m);
    }

    public synchronized boolean remove(String kid) {
        Entry e = entries.get(kid);
        if (e == null) {
            return false;
        }
        if (e.active()) {
            throw new SsoException("keyring.cannot_remove_active");
        }
        Map<String, Entry> m = new LinkedHashMap<>(entries);
        m.remove(kid);
        entries = Collections.unmodifiableMap(m);
        return true;
    }

    public synchronized int prune() {
        Instant now = clock.instant();
        Map<String, Entry> m = new LinkedHashMap<>();
        int removed = 0;
        for (Entry e : entries.values()) {
            if (e.usableAt(now)) {
                m.put(e.key().kid(), e);
            } else {
                removed++;
            }
        }
        entries = Collections.unmodifiableMap(m);
        return removed;
    }
}
