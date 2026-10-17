package com.naqqa.sso.mfa;

public enum OtpAlgorithm {
    SHA1("HmacSHA1", "SHA1"),
    SHA256("HmacSHA256", "SHA256"),
    SHA512("HmacSHA512", "SHA512");

    private final String macName;
    private final String uriName;

    OtpAlgorithm(String macName, String uriName) {
        this.macName = macName;
        this.uriName = uriName;
    }

    public String macName() {
        return macName;
    }

    public String uriName() {
        return uriName;
    }
}
