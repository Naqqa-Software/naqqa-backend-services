package com.naqqa.sso.oidc;

import java.time.Instant;

public record PendingAuthorization(String handleHash, String tenantId, AuthorizationParams params, Instant createdAt,
                                   Instant expiresAt, boolean viaPar) {
}
