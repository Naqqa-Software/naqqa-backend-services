package com.naqqa.sso.webauthn;

public enum ResidentKey {
    REQUIRED("required"), PREFERRED("preferred"), DISCOURAGED("discouraged");

    private final String value;

    ResidentKey(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
