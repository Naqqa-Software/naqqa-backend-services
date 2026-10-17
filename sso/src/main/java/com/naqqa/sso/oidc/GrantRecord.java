package com.naqqa.sso.oidc;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public record GrantRecord(String grantId, String tenantId, String clientId, String userId, Set<String> scopes, String sid,
                          String acr, Set<String> amr, Instant authTime, String dpopJkt, String refreshFamilyId,
                          String currentRefreshHash, Instant refreshExpiresAt, Map<String, Instant> accessTokens,
                          boolean revoked) {
    public GrantRecord {
        scopes = Set.copyOf(scopes);
        amr = Set.copyOf(amr == null ? Set.of() : amr);
        accessTokens = Map.copyOf(accessTokens == null ? Map.of() : accessTokens);
    }

    public GrantRecord withAccessToken(String jti, Instant exp, Instant now) {
        Map<String, Instant> m = new LinkedHashMap<>();
        for (Map.Entry<String, Instant> e : accessTokens.entrySet()) {
            if (e.getValue().isAfter(now)) {
                m.put(e.getKey(), e.getValue());
            }
        }
        m.put(jti, exp);
        return new GrantRecord(grantId, tenantId, clientId, userId, scopes, sid, acr, amr, authTime, dpopJkt, refreshFamilyId,
                currentRefreshHash, refreshExpiresAt, m, revoked);
    }

    public GrantRecord withRefresh(String familyId, String refreshHash, Instant expiresAt) {
        return new GrantRecord(grantId, tenantId, clientId, userId, scopes, sid, acr, amr, authTime, dpopJkt, familyId,
                refreshHash, expiresAt, accessTokens, revoked);
    }

    public GrantRecord asRevoked() {
        return new GrantRecord(grantId, tenantId, clientId, userId, scopes, sid, acr, amr, authTime, dpopJkt, refreshFamilyId,
                currentRefreshHash, refreshExpiresAt, accessTokens, true);
    }

    public boolean offline() {
        return scopes.contains(Scopes.OFFLINE_ACCESS);
    }
}
