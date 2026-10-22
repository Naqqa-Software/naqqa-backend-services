package com.naqqa.sso.saml.sp;

import java.time.Instant;

public interface SamlRequestStore {
    void save(String requestId, Instant expiresAt);

    boolean consume(String requestId);
}
