package com.naqqa.sso.tokens;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class ApiTokenService {
    public static final int MAX_SCOPES = 100;
    public static final int MAX_ALLOWLIST = 100;

    public record Config(int iterations, int maxFailures, Duration lockout, Duration cacheTtl, int cacheMaxEntries, Duration maxGrace) {
        public static final Config DEFAULT = new Config(ApiTokenHasher.DEFAULT_ITERATIONS, 10, Duration.ofMinutes(15),
                Duration.ofSeconds(60), 10_000, Duration.ofDays(7));

        public Config {
            if (maxFailures < 1 || lockout == null || lockout.isNegative() || cacheTtl == null || cacheTtl.isNegative()
                    || cacheTtl.compareTo(Duration.ofMinutes(5)) > 0 || cacheMaxEntries < 0 || maxGrace == null || maxGrace.isNegative()) {
                throw new SsoException("token.config_invalid");
            }
        }
    }

    public record Issued(String token, ApiTokenRecord record) {
        @Override
        public String toString() {
            return "Issued[" + record.id() + "]";
        }
    }

    public record Alert(String tokenId, int failures, Instant at) {
    }

    private record Attempts(int count, Instant last, Instant blockedUntil) {
    }

    private static final int MAX_TRACKED = 50_000;
    private static final int IP_FACTOR = 5;

    private record CacheEntry(String id, String matchedHash, Instant expiresAt) {
    }

    private final ApiTokenStore store;
    private final ApiTokenHasher hasher;
    private final Clock clock;
    private final Config config;
    private final String dummyHash;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final Map<String, Attempts> pairFailures = new ConcurrentHashMap<>();
    private final Map<String, Attempts> ipFailures = new ConcurrentHashMap<>();
    private final Consumer<Alert> alerts;

    public ApiTokenService(ApiTokenStore store, byte[] pepper, Clock clock) {
        this(store, pepper, clock, Config.DEFAULT);
    }

    public ApiTokenService(ApiTokenStore store, byte[] pepper, Clock clock, Config config) {
        this(store, pepper, clock, config, a -> {
        });
    }

    public ApiTokenService(ApiTokenStore store, byte[] pepper, Clock clock, Config config, Consumer<Alert> alerts) {
        if (store == null || clock == null || config == null) {
            throw new SsoException("token.config_invalid");
        }
        this.store = store;
        this.clock = clock;
        this.config = config;
        this.alerts = alerts == null ? a -> {
        } : alerts;
        this.hasher = new ApiTokenHasher(pepper, config.iterations());
        this.dummyHash = hasher.hash(Base62.random(ApiToken.SECRET_LENGTH));
    }

    public Issued issue(String tenantId, ApiTokenKind kind, ApiTokenEnv env, String name, Collection<String> scopes, Collection<String> allowlist) {
        if (tenantId == null || tenantId.isBlank() || tenantId.length() > 128) {
            throw new SsoException("token.tenant_invalid");
        }
        Set<String> scopeSet = validateScopes(scopes);
        List<Cidr> cidrs = parseAllowlist(allowlist);
        ApiToken token;
        do {
            token = ApiToken.generate(kind, env);
        } while (store.findById(token.id()).isPresent());
        Instant now = clock.instant();
        ApiTokenRecord record = new ApiTokenRecord(token.id(), tenantId, kind, env, name == null ? "" : name, scopeSet, cidrs,
                hasher.hash(token.secret()), null, null, now, null, null, 0, null);
        store.save(record);
        return new Issued(token.encode(), record);
    }

    public ApiTokenPrincipal authenticate(String presented, String clientIp) {
        ApiToken token;
        try {
            token = ApiToken.parse(presented);
        } catch (SsoException e) {
            throw new SsoException("token.malformed");
        }
        Instant now = clock.instant();
        String cacheKey = Bytes.hex(Hashes.sha256(Bytes.utf8(presented)));
        ApiTokenRecord record = store.findById(token.id()).orElse(null);
        if (record == null) {
            hasher.verify(token.secret(), dummyHash);
            cache.remove(cacheKey);
            Attempts source = noteSource(clientIp, now);
            throw new SsoException(source.blockedUntil() != null && now.isBefore(source.blockedUntil()) ? "token.throttled" : "token.invalid");
        }
        CacheEntry cached = cache.get(cacheKey);
        boolean verified = false;
        if (cached != null) {
            if (cached.expiresAt().isAfter(now) && cached.id().equals(record.id()) && hashStillValid(record, cached.matchedHash(), now)) {
                verified = true;
            } else {
                cache.remove(cacheKey);
            }
        }
        if (!verified) {
            String matched = null;
            if (hasher.verify(token.secret(), record.hash())) {
                matched = record.hash();
            } else if (record.previousHash() != null && record.previousValidUntil() != null
                    && now.isBefore(record.previousValidUntil()) && hasher.verify(token.secret(), record.previousHash())) {
                matched = record.previousHash();
            }
            if (matched == null || record.kind() != token.kind() || record.env() != token.env()) {
                boolean throttled = registerFailure(record, clientIp, now);
                throw new SsoException(throttled ? "token.throttled" : "token.invalid");
            }
            pairFailures.remove(pairKey(record.id(), clientIp));
            if (record.failedAttempts() > 0 || record.lockedUntil() != null) {
                store.resetFailures(record.id());
            }
            putCache(cacheKey, new CacheEntry(record.id(), matched, now.plus(config.cacheTtl())), now);
        }
        if (record.revoked()) {
            cache.remove(cacheKey);
            throw new SsoException("token.revoked");
        }
        if (!record.allowlist().isEmpty() && !ipAllowed(record.allowlist(), clientIp)) {
            throw new SsoException("token.ip_denied");
        }
        return new ApiTokenPrincipal(record.id(), record.tenantId(), record.kind(), record.env(), record.scopes());
    }

    private static boolean hashStillValid(ApiTokenRecord r, String matched, Instant now) {
        if (matched.equals(r.hash())) {
            return true;
        }
        return matched.equals(r.previousHash()) && r.previousValidUntil() != null && now.isBefore(r.previousValidUntil());
    }

    private static String pairKey(String id, String ip) {
        return id + "|" + (ip == null ? "" : ip);
    }

    private Attempts bump(Map<String, Attempts> map, String key, int limit, Instant now) {
        if (map.size() >= MAX_TRACKED) {
            map.values().removeIf(a -> !now.isBefore(a.last().plus(config.lockout())) && (a.blockedUntil() == null || !now.isBefore(a.blockedUntil())));
            if (map.size() >= MAX_TRACKED) {
                map.clear();
            }
        }
        return map.compute(key, (k, prev) -> {
            int count = prev == null || !now.isBefore(prev.last().plus(config.lockout())) ? 1 : prev.count() + 1;
            Instant blocked = null;
            if (count >= limit) {
                long shift = Math.min(count - limit, 20);
                Duration delay = Duration.ofSeconds(1L << shift);
                if (delay.compareTo(config.lockout()) > 0) {
                    delay = config.lockout();
                }
                blocked = now.plus(delay);
            }
            return new Attempts(count, now, blocked);
        });
    }

    private Attempts noteSource(String clientIp, Instant now) {
        return bump(ipFailures, clientIp == null ? "" : clientIp, config.maxFailures() * IP_FACTOR, now);
    }

    private boolean registerFailure(ApiTokenRecord record, String clientIp, Instant now) {
        Attempts pair = bump(pairFailures, pairKey(record.id(), clientIp), config.maxFailures(), now);
        Attempts ip = bump(ipFailures, clientIp == null ? "" : clientIp, config.maxFailures() * IP_FACTOR, now);
        int failures = store.recordFailure(record.id());
        if (failures >= config.maxFailures()) {
            store.resetFailures(record.id());
            alerts.accept(new Alert(record.id(), failures, now));
        }
        return pair.blockedUntil() != null && now.isBefore(pair.blockedUntil())
                || ip.blockedUntil() != null && now.isBefore(ip.blockedUntil());
    }

    private void putCache(String key, CacheEntry entry, Instant now) {
        if (config.cacheMaxEntries() == 0 || config.cacheTtl().isZero()) {
            return;
        }
        if (cache.size() >= config.cacheMaxEntries()) {
            cache.values().removeIf(e -> !e.expiresAt().isAfter(now));
            if (cache.size() >= config.cacheMaxEntries()) {
                cache.clear();
            }
        }
        cache.put(key, entry);
    }

    private static boolean ipAllowed(List<Cidr> allowlist, String clientIp) {
        byte[] addr;
        try {
            addr = IpAddresses.parse(clientIp);
        } catch (SsoException e) {
            return false;
        }
        for (Cidr c : allowlist) {
            if (c.contains(addr)) {
                return true;
            }
        }
        return false;
    }

    public Issued rotate(String id, Duration grace) {
        if (grace == null || grace.isNegative() || grace.compareTo(config.maxGrace()) > 0) {
            throw new SsoException("token.grace_invalid");
        }
        ApiTokenRecord record = store.findById(id).orElseThrow(() -> new SsoException("token.not_found"));
        if (record.revoked()) {
            throw new SsoException("token.revoked");
        }
        Instant now = clock.instant();
        ApiToken next = new ApiToken(record.kind(), record.env(), record.id(), Base62.random(ApiToken.SECRET_LENGTH));
        String oldHash = grace.isZero() ? null : record.hash();
        Instant oldUntil = grace.isZero() ? null : now.plus(grace);
        ApiTokenRecord updated = record.withHashes(hasher.hash(next.secret()), oldHash, oldUntil, now);
        if (!store.rotate(id, record.hash(), updated.hash(), oldHash, oldUntil, now)) {
            throw new SsoException(store.findById(id).map(ApiTokenRecord::revoked).orElse(false) ? "token.revoked" : "token.conflict");
        }
        updated = store.findById(id).orElse(updated);
        if (grace.isZero()) {
            evict(id);
        }
        return new Issued(next.encode(), updated);
    }

    public void revoke(String id) {
        ApiTokenRecord record = store.findById(id).orElseThrow(() -> new SsoException("token.not_found"));
        if (!record.revoked()) {
            store.revoke(id, clock.instant());
        }
        evict(id);
    }

    public ApiTokenRecord updateScopes(String id, Collection<String> scopes) {
        Set<String> valid = validateScopes(scopes);
        if (!store.updateScopes(id, valid)) {
            throw new SsoException("token.not_found");
        }
        return store.findById(id).orElseThrow(() -> new SsoException("token.not_found"));
    }

    public ApiTokenRecord updateAllowlist(String id, Collection<String> allowlist) {
        List<Cidr> valid = parseAllowlist(allowlist);
        if (!store.updateAllowlist(id, valid)) {
            throw new SsoException("token.not_found");
        }
        return store.findById(id).orElseThrow(() -> new SsoException("token.not_found"));
    }

    public List<ApiTokenRecord> list(String tenantId) {
        return store.findByTenant(tenantId);
    }

    private void evict(String id) {
        cache.values().removeIf(e -> e.id().equals(id));
    }

    static Set<String> validateScopes(Collection<String> scopes) {
        if (scopes == null) {
            return Set.of();
        }
        if (scopes.size() > MAX_SCOPES) {
            throw new SsoException("token.scope_invalid");
        }
        for (String s : scopes) {
            if (s == null || s.isEmpty() || s.length() > 64 || !s.matches("[a-z0-9][a-z0-9:._-]*")) {
                throw new SsoException("token.scope_invalid");
            }
        }
        return Set.copyOf(scopes);
    }

    static List<Cidr> parseAllowlist(Collection<String> allowlist) {
        List<Cidr> out = new ArrayList<>();
        if (allowlist == null) {
            return out;
        }
        if (allowlist.size() > MAX_ALLOWLIST) {
            throw new SsoException("token.allowlist_invalid");
        }
        for (String s : allowlist) {
            out.add(Cidr.parse(s));
        }
        return out;
    }
}
