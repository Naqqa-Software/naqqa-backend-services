package com.naqqa.sso.crypto;

import com.naqqa.sso.SsoException;

public enum SigningAlgorithm {
    RS256("RS256", "SHA256withRSA", "RSA"),
    ES256("ES256", "SHA256withECDSA", "EC"),
    EdDSA("EdDSA", "Ed25519", "OKP");

    private final String jwa;
    private final String jca;
    private final String kty;

    SigningAlgorithm(String jwa, String jca, String kty) {
        this.jwa = jwa;
        this.jca = jca;
        this.kty = kty;
    }

    public String jwa() {
        return jwa;
    }

    public String jca() {
        return jca;
    }

    public String kty() {
        return kty;
    }

    public static SigningAlgorithm fromJwa(String name) {
        for (SigningAlgorithm a : values()) {
            if (a.jwa.equals(name)) {
                return a;
            }
        }
        throw new SsoException("crypto.unsupported_alg");
    }
}
