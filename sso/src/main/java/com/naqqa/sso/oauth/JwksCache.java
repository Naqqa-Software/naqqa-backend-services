package com.naqqa.sso.oauth;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.crypto.SigningKey;
import com.naqqa.sso.jose.Jwks;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class JwksCache {
    public static final Duration MIN_TTL = Duration.ofMinutes(5);
    public static final Duration MAX_TTL = Duration.ofHours(24);
    public static final Duration DEFAULT_TTL = Duration.ofHours(1);
    public static final Duration REFETCH_INTERVAL = Duration.ofMinutes(1);
    public static final Duration FAILURE_BACKOFF = Duration.ofSeconds(10);
    private static final int MAX_ENTRIES = 64;
    private static final List<String> TYPES = List.of("application/json", "application/jwk-set+json");

    private record Entry(List<SigningKey> keys, Instant expiresAt, Instant lastFetch) {
    }

    private final SafeHttp http;
    private final Clock clock;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public JwksCache(SafeHttp http, Clock clock) {
        this.http = http;
        this.clock = clock;
    }

    public SigningKey find(String jwksUri, String kid) {
        if (jwksUri == null) {
            throw new SsoException("oauth.url_invalid");
        }
        if (locks.size() > MAX_ENTRIES * 4) {
            locks.clear();
        }
        synchronized (locks.computeIfAbsent(jwksUri, k -> new Object())) {
            return findLocked(jwksUri, kid);
        }
    }

    private SigningKey findLocked(String jwksUri, String kid) {
        Instant now = clock.instant();
        Entry e = entries.get(jwksUri);
        if (e == null || !now.isBefore(e.expiresAt())) {
            e = fetch(jwksUri, now, e);
        }
        SigningKey key = lookup(e.keys(), kid);
        if (key == null && !now.isBefore(e.lastFetch().plus(REFETCH_INTERVAL))) {
            e = fetch(jwksUri, now, e);
            key = lookup(e.keys(), kid);
        }
        if (key == null) {
            throw new SsoException("oauth.jwks_kid_unknown");
        }
        return key;
    }

    public void invalidate(String jwksUri) {
        entries.remove(jwksUri);
    }

    private static SigningKey lookup(List<SigningKey> keys, String kid) {
        if (kid == null) {
            return keys.size() == 1 ? keys.get(0) : null;
        }
        for (SigningKey k : keys) {
            if (k.kid().equals(kid)) {
                return k;
            }
        }
        return null;
    }

    private Entry fetch(String uri, Instant now, Entry previous) {
        Entry stamp = new Entry(previous == null ? List.of() : previous.keys(), now.plus(FAILURE_BACKOFF), now);
        try {
            SafeHttp.Response r = http.get(uri, Map.of(), TYPES);
            if (r.status() != 200) {
                throw new SsoException("oauth.jwks_unavailable");
            }
            List<SigningKey> keys = Jwks.parse(r.bodyUtf8());
            Entry fresh = new Entry(List.copyOf(keys), now.plus(ttl(r.header("cache-control"))), now);
            if (entries.size() >= MAX_ENTRIES && !entries.containsKey(uri)) {
                entries.clear();
            }
            entries.put(uri, fresh);
            return fresh;
        } catch (SsoException e) {
            entries.put(uri, stamp);
            throw e;
        }
    }

    static Duration ttl(String cacheControl) {
        if (cacheControl == null) {
            return DEFAULT_TTL;
        }
        Duration ttl = DEFAULT_TTL;
        for (String part : cacheControl.split(",")) {
            String p = part.trim().toLowerCase(Locale.ROOT);
            if (p.equals("no-store") || p.equals("no-cache")) {
                return MIN_TTL;
            }
            if (p.startsWith("max-age=")) {
                try {
                    long s = Long.parseLong(p.substring(8).replace("\"", "").trim());
                    ttl = Duration.ofSeconds(Math.max(0, Math.min(s, MAX_TTL.getSeconds())));
                } catch (NumberFormatException ex) {
                    ttl = DEFAULT_TTL;
                }
            }
        }
        if (ttl.compareTo(MIN_TTL) < 0) {
            return MIN_TTL;
        }
        return ttl.compareTo(MAX_TTL) > 0 ? MAX_TTL : ttl;
    }
}
