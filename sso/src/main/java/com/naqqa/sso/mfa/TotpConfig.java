package com.naqqa.sso.mfa;

import com.naqqa.sso.SsoException;

public record TotpConfig(OtpAlgorithm algorithm, int digits, int stepSeconds, int window) {
    public TotpConfig {
        if (algorithm == null || digits < 6 || digits > 8 || stepSeconds < 1 || window < 0 || window > 5) {
            throw new SsoException("totp_config_invalid");
        }
    }

    public static TotpConfig defaults() {
        return new TotpConfig(OtpAlgorithm.SHA1, 6, 30, 1);
    }
}
