package com.naqqa.sso.tokens;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public record ApiTokenRecord(
        String id,
        String tenantId,
        ApiTokenKind kind,
        ApiTokenEnv env,
        String name,
        Set<String> scopes,
        List<Cidr> allowlist,
        String hash,
        String previousHash,
        Instant previousValidUntil,
        Instant createdAt,
        Instant rotatedAt,
        Instant revokedAt,
        int failedAttempts,
        Instant lockedUntil) {

    public ApiTokenRecord {
        scopes = Set.copyOf(scopes);
        allowlist = List.copyOf(allowlist);
    }

    public boolean revoked() {
        return revokedAt != null;
    }

    public boolean lockedAt(Instant now) {
        return lockedUntil != null && now.isBefore(lockedUntil);
    }

    public ApiTokenRecord withFailures(int failures, Instant locked) {
        return new ApiTokenRecord(id, tenantId, kind, env, name, scopes, allowlist, hash, previousHash, previousValidUntil,
                createdAt, rotatedAt, revokedAt, failures, locked);
    }

    public ApiTokenRecord withHashes(String newHash, String oldHash, Instant oldValidUntil, Instant rotated) {
        return new ApiTokenRecord(id, tenantId, kind, env, name, scopes, allowlist, newHash, oldHash, oldValidUntil,
                createdAt, rotated, revokedAt, failedAttempts, lockedUntil);
    }

    public ApiTokenRecord withRevokedAt(Instant when) {
        return new ApiTokenRecord(id, tenantId, kind, env, name, scopes, allowlist, hash, previousHash, previousValidUntil,
                createdAt, rotatedAt, when, failedAttempts, lockedUntil);
    }

    public ApiTokenRecord withScopes(Set<String> newScopes) {
        return new ApiTokenRecord(id, tenantId, kind, env, name, newScopes, allowlist, hash, previousHash, previousValidUntil,
                createdAt, rotatedAt, revokedAt, failedAttempts, lockedUntil);
    }

    public ApiTokenRecord withAllowlist(List<Cidr> newAllowlist) {
        return new ApiTokenRecord(id, tenantId, kind, env, name, scopes, newAllowlist, hash, previousHash, previousValidUntil,
                createdAt, rotatedAt, revokedAt, failedAttempts, lockedUntil);
    }

    @Override
    public String toString() {
        return "ApiTokenRecord[" + id + "," + tenantId + "," + kind + "," + env + "]";
    }
}
