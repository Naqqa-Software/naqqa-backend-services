package com.naqqa.authenticator.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

public final class Tokens {

    private final SecureRandom random;

    public Tokens(SecureRandom random) {
        this.random = random;
    }

    public byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }

    public String opaque(int byteLength) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(byteLength));
    }

    public String id(String prefix) {
        return prefix + "_" + opaque(16);
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
