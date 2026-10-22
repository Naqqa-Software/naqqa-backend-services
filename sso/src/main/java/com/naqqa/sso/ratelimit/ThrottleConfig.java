package com.naqqa.sso.ratelimit;

import com.naqqa.sso.SsoException;

import java.time.Duration;

public record ThrottleConfig(int freeAttempts, Duration baseDelay, Duration maxDelay, Duration decay, int accountChallengeThreshold,
                             int ipChallengeThreshold, int pairChallengeThreshold) {
    public ThrottleConfig {
        if (freeAttempts < 0 || baseDelay.isNegative() || maxDelay.compareTo(baseDelay) < 0 || decay.isNegative() || decay.isZero()
                || accountChallengeThreshold < 1 || ipChallengeThreshold < 1 || pairChallengeThreshold < 1) {
            throw new SsoException("ratelimit_config_invalid");
        }
    }

    public static ThrottleConfig defaults() {
        return new ThrottleConfig(3, Duration.ofSeconds(1), Duration.ofMinutes(15), Duration.ofMinutes(30), 10, 20, 6);
    }
}
