package com.naqqa.sso.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

public final class Bytes {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64URL_DEC = Base64.getUrlDecoder();

    private Bytes() {
    }

    public static byte[] random(int length) {
        byte[] out = new byte[length];
        RANDOM.nextBytes(out);
        return out;
    }

    public static String randomToken(int byteLength) {
        return b64url(random(byteLength));
    }

    public static String b64url(byte[] data) {
        return B64URL.encodeToString(data);
    }

    public static byte[] b64urlDecode(String value) {
        if (value == null || value.indexOf('=') >= 0 || value.indexOf('+') >= 0 || value.indexOf('/') >= 0) {
            throw new IllegalArgumentException("invalid base64url");
        }
        byte[] out = B64URL_DEC.decode(value);
        if (!b64url(out).equals(value)) {
            throw new IllegalArgumentException("non-canonical base64url");
        }
        return out;
    }

    public static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    public static String utf8(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }

    public static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a, b);
    }

    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(utf8(a), utf8(b));
    }

    public static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] p : parts) {
            length += p.length;
        }
        byte[] out = new byte[length];
        int offset = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, offset, p.length);
            offset += p.length;
        }
        return out;
    }

    public static void wipe(byte[] data) {
        if (data != null) {
            Arrays.fill(data, (byte) 0);
        }
    }

    public static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
