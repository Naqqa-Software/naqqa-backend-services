package com.naqqa.authenticator.domain;

public enum FactorType {

    TOTP("totp");

    private final String wireName;

    FactorType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
