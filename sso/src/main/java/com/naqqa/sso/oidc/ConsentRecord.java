package com.naqqa.sso.oidc;

import java.time.Instant;
import java.util.Set;

public record ConsentRecord(String tenantId, String userId, String clientId, Set<String> scopes, Instant grantedAt) {
    public ConsentRecord {
        scopes = Set.copyOf(scopes);
    }

    public boolean covers(Set<String> requested) {
        return scopes.containsAll(requested);
    }
}
