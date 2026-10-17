package com.naqqa.sso.saml;

import java.time.Instant;

public interface ReplayCache {
    boolean markIfAbsent(String key, Instant expiresAt);
}
