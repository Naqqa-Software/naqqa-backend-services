package com.naqqa.sso.oidc;

import java.time.Instant;
import java.util.Set;

public record AuthorizationCode(String codeHash, String tenantId, String clientId, String redirectUri, String codeChallenge,
                                String nonce, String userId, String sid, Set<String> scopes, String acr, Set<String> amr,
                                Instant authTime, Instant issuedAt, Instant expiresAt, String dpopJkt, String grantId,
                                boolean used) {
    public AuthorizationCode {
        scopes = Set.copyOf(scopes);
        amr = Set.copyOf(amr);
    }

    public AuthorizationCode markUsed() {
        return new AuthorizationCode(codeHash, tenantId, clientId, redirectUri, codeChallenge, nonce, userId, sid, scopes, acr, amr,
                authTime, issuedAt, expiresAt, dpopJkt, grantId, true);
    }
}
