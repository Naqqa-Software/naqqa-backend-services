package com.naqqa.sso.tokens;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryApiTokenStore implements ApiTokenStore {
    private final Map<String, ApiTokenRecord> records = new ConcurrentHashMap<>();

    @Override
    public Optional<ApiTokenRecord> findById(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(records.get(id));
    }

    @Override
    public List<ApiTokenRecord> findByTenant(String tenantId) {
        List<ApiTokenRecord> out = new ArrayList<>();
        for (ApiTokenRecord r : records.values()) {
            if (r.tenantId().equals(tenantId)) {
                out.add(r);
            }
        }
        return out;
    }

    @Override
    public void save(ApiTokenRecord record) {
        records.merge(record.id(), record, (current, next) -> current.revoked() && !next.revoked() ? next.withRevokedAt(current.revokedAt()) : next);
    }

    @Override
    public boolean delete(String id) {
        return records.remove(id) != null;
    }

    @Override
    public int recordFailure(String id) {
        int[] count = {0};
        records.computeIfPresent(id, (k, r) -> {
            count[0] = r.failedAttempts() + 1;
            return r.withFailures(count[0], r.lockedUntil());
        });
        return count[0];
    }

    @Override
    public void resetFailures(String id) {
        records.computeIfPresent(id, (k, r) -> r.failedAttempts() == 0 && r.lockedUntil() == null ? r : r.withFailures(0, null));
    }

    @Override
    public boolean revoke(String id, Instant at) {
        boolean[] flipped = {false};
        records.computeIfPresent(id, (k, r) -> {
            if (r.revoked()) {
                return r;
            }
            flipped[0] = true;
            return r.withRevokedAt(at);
        });
        return flipped[0];
    }

    @Override
    public boolean rotate(String id, String expectedHash, String newHash, String previousHash, Instant previousValidUntil, Instant rotatedAt) {
        boolean[] done = {false};
        records.computeIfPresent(id, (k, r) -> {
            if (r.revoked() || !r.hash().equals(expectedHash)) {
                return r;
            }
            done[0] = true;
            return r.withHashes(newHash, previousHash, previousValidUntil, rotatedAt);
        });
        return done[0];
    }

    @Override
    public boolean updateScopes(String id, Set<String> scopes) {
        return records.computeIfPresent(id, (k, r) -> r.withScopes(scopes)) != null;
    }

    @Override
    public boolean updateAllowlist(String id, List<Cidr> allowlist) {
        return records.computeIfPresent(id, (k, r) -> r.withAllowlist(allowlist)) != null;
    }
}
