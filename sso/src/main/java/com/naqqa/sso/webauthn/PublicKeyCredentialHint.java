package com.naqqa.sso.webauthn;

public enum PublicKeyCredentialHint {
    SECURITY_KEY("security-key"), CLIENT_DEVICE("client-device"), HYBRID("hybrid");

    private final String value;

    PublicKeyCredentialHint(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
