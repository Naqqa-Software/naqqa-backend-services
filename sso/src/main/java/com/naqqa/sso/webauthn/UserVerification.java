package com.naqqa.sso.webauthn;

public enum UserVerification {
    REQUIRED("required"), PREFERRED("preferred"), DISCOURAGED("discouraged");

    private final String value;

    UserVerification(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
