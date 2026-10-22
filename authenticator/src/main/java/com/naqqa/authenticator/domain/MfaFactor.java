package com.naqqa.authenticator.domain;

import com.naqqa.authenticator.core.HmacAlgorithm;
import com.naqqa.authenticator.core.TotpParameters;

import java.time.Instant;

public record MfaFactor(
        String id,
        String userId,
        FactorType type,
        String label,
        String secretCiphertext,
        HmacAlgorithm algorithm,
        int digits,
        int periodSeconds,
        long lastUsedCounter,
        Instant createdAt,
        Instant lastUsedAt) {

    public TotpParameters parameters(int skewSteps) {
        return new TotpParameters(algorithm, digits, periodSeconds, skewSteps);
    }

    public MfaFactor withUse(long counter, Instant usedAt) {
        return new MfaFactor(id, userId, type, label, secretCiphertext, algorithm, digits, periodSeconds,
                counter, createdAt, usedAt);
    }
}
