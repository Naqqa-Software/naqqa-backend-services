package com.naqqa.authenticator.core;

import java.util.Locale;

public enum HmacAlgorithm {

    SHA1("HmacSHA1", "SHA1"),
    SHA256("HmacSHA256", "SHA256"),
    SHA512("HmacSHA512", "SHA512");

    private final String jcaName;
    private final String uriName;

    HmacAlgorithm(String jcaName, String uriName) {
        this.jcaName = jcaName;
        this.uriName = uriName;
    }

    public String jcaName() {
        return jcaName;
    }

    public String uriName() {
        return uriName;
    }

    public static HmacAlgorithm fromName(String name) {
        if (name == null) {
            throw new IllegalArgumentException("algorithm is null");
        }
        String normalized = name.trim().toUpperCase(Locale.ROOT).replace("-", "").replace("HMAC", "");
        for (HmacAlgorithm algorithm : values()) {
            if (algorithm.uriName.equals(normalized)) {
                return algorithm;
            }
        }
        throw new IllegalArgumentException("unsupported algorithm " + name);
    }
}
