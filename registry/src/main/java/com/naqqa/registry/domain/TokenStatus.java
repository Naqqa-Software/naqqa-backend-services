package com.naqqa.registry.domain;

public enum TokenStatus {

    ACTIVE("active"),
    EXPIRED("expired"),
    REVOKED("revoked");

    private final String value;

    TokenStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
