package com.naqqa.sso.tokens;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.Optional;

public interface ApiTokenStore {
    Optional<ApiTokenRecord> findById(String id);

    List<ApiTokenRecord> findByTenant(String tenantId);

    void save(ApiTokenRecord record);

    boolean delete(String id);

    int recordFailure(String id);

    void resetFailures(String id);

    boolean revoke(String id, Instant at);

    boolean rotate(String id, String expectedHash, String newHash, String previousHash, Instant previousValidUntil, Instant rotatedAt);

    boolean updateScopes(String id, Set<String> scopes);

    boolean updateAllowlist(String id, List<Cidr> allowlist);
}
