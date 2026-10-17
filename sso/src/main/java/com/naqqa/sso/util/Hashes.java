package com.naqqa.sso.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

public final class Hashes {
    private Hashes() {
    }

    public static byte[] sha256(byte[] data) {
        return digest("SHA-256", data);
    }

    public static byte[] sha384(byte[] data) {
        return digest("SHA-384", data);
    }

    public static byte[] sha512(byte[] data) {
        return digest("SHA-512", data);
    }

    public static byte[] digest(String algorithm, byte[] data) {
        try {
            return MessageDigest.getInstance(algorithm).digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] hmac(String algorithm, byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec(key, algorithm));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] hmacSha256(byte[] key, byte[] data) {
        return hmac("HmacSHA256", key, data);
    }

    public static byte[] hkdfSha256(byte[] ikm, byte[] salt, byte[] info, int length) {
        if (length <= 0 || length > 255 * 32) {
            throw new IllegalArgumentException("invalid hkdf length");
        }
        byte[] prk = hmacSha256(salt == null || salt.length == 0 ? new byte[32] : salt, ikm);
        byte[] out = new byte[length];
        byte[] t = new byte[0];
        int offset = 0;
        for (int i = 1; offset < length; i++) {
            t = hmacSha256(prk, Bytes.concat(t, info == null ? new byte[0] : info, new byte[]{(byte) i}));
            int n = Math.min(t.length, length - offset);
            System.arraycopy(t, 0, out, offset, n);
            offset += n;
        }
        Bytes.wipe(prk);
        return out;
    }
}
