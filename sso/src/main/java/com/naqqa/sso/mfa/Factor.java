package com.naqqa.sso.mfa;

import java.util.List;

public enum Factor {
    PASSWORD("pwd"),
    TOTP("otp"),
    HOTP("otp"),
    EMAIL_OTP("otp"),
    MAGIC_LINK("otp"),
    RECOVERY_CODE("otp"),
    SMS_OTP("sms"),
    PUSH("mca"),
    SECURITY_KEY("hwk", "user"),
    PASSKEY("swk", "user"),
    PLATFORM_FACE("swk", "face", "user"),
    PLATFORM_FINGERPRINT("swk", "fpt", "user");

    private final List<String> amr;

    Factor(String... amr) {
        this.amr = List.of(amr);
    }

    public List<String> amr() {
        return amr;
    }
}
