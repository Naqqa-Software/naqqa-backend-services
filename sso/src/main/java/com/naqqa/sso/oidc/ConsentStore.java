package com.naqqa.sso.oidc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface ConsentStore {
    Optional<ConsentRecord> find(String tenantId, String userId, String clientId);

    void save(ConsentRecord record);

    void grant(String tenantId, String userId, String clientId, Set<String> scopes, Instant at);

    boolean revoke(String tenantId, String userId, String clientId);

    List<ConsentRecord> listByUser(String tenantId, String userId);
}
