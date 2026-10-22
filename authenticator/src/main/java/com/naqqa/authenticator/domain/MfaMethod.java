package com.naqqa.authenticator.domain;

import java.util.Locale;
import java.util.Optional;

public enum MfaMethod {

    TOTP("totp"),
    RECOVERY("recovery");

    private final String wireName;

    MfaMethod(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static Optional<MfaMethod> fromWire(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (MfaMethod method : values()) {
            if (method.wireName.equals(normalized)) {
                return Optional.of(method);
            }
        }
        return Optional.empty();
    }
}
