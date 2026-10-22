package com.naqqa.authenticator.core;

public record TotpParameters(HmacAlgorithm algorithm, int digits, int periodSeconds, int skewSteps) {

    public static final TotpParameters DEFAULT = new TotpParameters(HmacAlgorithm.SHA1, 6, 30, 1);

    public TotpParameters {
        if (algorithm == null) {
            throw new IllegalArgumentException("algorithm is required");
        }
        if (digits < Hotp.MIN_DIGITS || digits > Hotp.MAX_DIGITS) {
            throw new IllegalArgumentException("digits must be between " + Hotp.MIN_DIGITS + " and " + Hotp.MAX_DIGITS);
        }
        if (periodSeconds < 15 || periodSeconds > 300) {
            throw new IllegalArgumentException("period must be between 15 and 300 seconds");
        }
        if (skewSteps < 0 || skewSteps > 3) {
            throw new IllegalArgumentException("skew must be between 0 and 3 steps");
        }
    }
}
