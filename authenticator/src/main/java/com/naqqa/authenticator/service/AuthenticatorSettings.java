package com.naqqa.authenticator.service;

import com.naqqa.authenticator.core.TotpParameters;

import java.time.Duration;

public record AuthenticatorSettings(
        String issuer,
        TotpParameters totp,
        int secretBytes,
        Duration enrollmentTtl,
        Duration challengeTtl,
        int maxAttempts,
        int recoveryCodeCount,
        String factorLabel) {

    public AuthenticatorSettings {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("issuer is required");
        }
        if (totp == null) {
            throw new IllegalArgumentException("totp parameters are required");
        }
        if (secretBytes < 16 || secretBytes > 64) {
            throw new IllegalArgumentException("secret length must be between 16 and 64 bytes");
        }
        if (enrollmentTtl == null || enrollmentTtl.isNegative() || enrollmentTtl.isZero()) {
            throw new IllegalArgumentException("enrollment ttl must be positive");
        }
        if (challengeTtl == null || challengeTtl.isNegative() || challengeTtl.isZero()) {
            throw new IllegalArgumentException("challenge ttl must be positive");
        }
        if (maxAttempts < 1 || maxAttempts > 20) {
            throw new IllegalArgumentException("max attempts must be between 1 and 20");
        }
        if (recoveryCodeCount < 4 || recoveryCodeCount > 20) {
            throw new IllegalArgumentException("recovery code count must be between 4 and 20");
        }
        if (factorLabel == null || factorLabel.isBlank()) {
            factorLabel = "Authenticator app";
        }
    }

    public static AuthenticatorSettings defaults() {
        return new AuthenticatorSettings("naqqa", TotpParameters.DEFAULT, 20, Duration.ofMinutes(10),
                Duration.ofMinutes(5), 5, 10, "Authenticator app");
    }
}
