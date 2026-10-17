package com.naqqa.sso.session;

import com.naqqa.sso.SsoException;

import java.time.Duration;

public record SessionConfig(Duration absoluteTimeout, Duration idleTimeout) {
    public SessionConfig {
        if (absoluteTimeout == null || idleTimeout == null || absoluteTimeout.isZero() || idleTimeout.isZero()
                || absoluteTimeout.isNegative() || idleTimeout.isNegative()) {
            throw new SsoException("session_config_invalid");
        }
    }

    public static SessionConfig defaults() {
        return new SessionConfig(Duration.ofDays(30), Duration.ofHours(8));
    }
}
