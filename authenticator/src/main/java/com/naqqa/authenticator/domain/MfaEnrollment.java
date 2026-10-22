package com.naqqa.authenticator.domain;

import com.naqqa.authenticator.core.HmacAlgorithm;

import java.time.Instant;

public record MfaEnrollment(
        String id,
        String userId,
        String secretCiphertext,
        HmacAlgorithm algorithm,
        int digits,
        int periodSeconds,
        int attempts,
        Instant createdAt,
        Instant expiresAt) {

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public MfaEnrollment withAttempts(int value) {
        return new MfaEnrollment(id, userId, secretCiphertext, algorithm, digits, periodSeconds, value, createdAt, expiresAt);
    }
}
