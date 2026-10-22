package com.naqqa.sso.mfa;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

public final class RememberedDevices {
    private record Entry(String userId, Instant expires) {
    }

    public static final int DEFAULT_MAX_ENTRIES = 100_000;

    private final Clock clock;
    private final Duration ttl;
    private final int maxEntries;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    public RememberedDevices(Clock clock, Duration ttl) {
        this(clock, ttl, DEFAULT_MAX_ENTRIES);
    }

    public RememberedDevices(Clock clock, Duration ttl, int maxEntries) {
        if (clock == null || ttl == null || ttl.isNegative() || ttl.isZero() || maxEntries < 1) {
            throw new SsoException("remembered_devices_config_invalid");
        }
        this.clock = clock;
        this.ttl = ttl;
        this.maxEntries = maxEntries;
    }

    public int size() {
        return entries.size();
    }

    public void purge() {
        Instant now = clock.instant();
        entries.values().removeIf(e -> !now.isBefore(e.expires()));
    }

    private static String key(String token) {
        return Bytes.b64url(Hashes.sha256(Bytes.utf8(token)));
    }

    public String issue(String userId) {
        if (entries.size() >= maxEntries) {
            purge();
            if (entries.size() >= maxEntries) {
                throw new SsoException("remembered_devices_capacity");
            }
        }
        String token = Bytes.randomToken(32);
        entries.put(key(token), new Entry(userId, clock.instant().plus(ttl)));
        return token;
    }

    public boolean isRemembered(String userId, String token) {
        if (token == null) {
            return false;
        }
        Entry e = entries.get(key(token));
        if (e == null) {
            return false;
        }
        if (!clock.instant().isBefore(e.expires())) {
            entries.remove(key(token));
            return false;
        }
        return Bytes.constantTimeEquals(e.userId(), userId);
    }

    public void revoke(String token) {
        entries.remove(key(token));
    }

    public void revokeAllForUser(String userId) {
        entries.values().removeIf(e -> e.userId().equals(userId));
    }
}
