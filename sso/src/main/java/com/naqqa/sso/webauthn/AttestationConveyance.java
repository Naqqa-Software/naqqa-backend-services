package com.naqqa.sso.webauthn;

public enum AttestationConveyance {
    NONE("none"), INDIRECT("indirect"), DIRECT("direct"), ENTERPRISE("enterprise");

    private final String value;

    AttestationConveyance(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
