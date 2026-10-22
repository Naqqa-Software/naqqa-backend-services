package com.naqqa.sso.otp;

import com.naqqa.sso.SsoException;

import java.time.Duration;

public record OtpConfig(int digits, Duration ttl, int maxAttempts, Duration resendCooldown, int dailyCap) {
    public OtpConfig {
        if (digits < 4 || digits > 10 || ttl.isNegative() || ttl.isZero() || maxAttempts < 1 || resendCooldown.isNegative() || dailyCap < 1) {
            throw new SsoException("otp_config_invalid");
        }
    }

    public static OtpConfig defaults() {
        return new OtpConfig(6, Duration.ofMinutes(10), 5, Duration.ofSeconds(60), 10);
    }
}
