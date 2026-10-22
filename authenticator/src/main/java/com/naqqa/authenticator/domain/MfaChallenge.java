package com.naqqa.authenticator.domain;

import java.time.Instant;
import java.util.Map;

public record MfaChallenge(
        String id,
        String userId,
        Map<String, String> context,
        int attempts,
        Instant createdAt,
        Instant expiresAt) {

    public MfaChallenge {
        context = context == null ? Map.of() : Map.copyOf(context);
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
