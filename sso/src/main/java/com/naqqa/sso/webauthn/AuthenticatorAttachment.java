package com.naqqa.sso.webauthn;

public enum AuthenticatorAttachment {
    PLATFORM("platform"), CROSS_PLATFORM("cross-platform");

    private final String value;

    AuthenticatorAttachment(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
