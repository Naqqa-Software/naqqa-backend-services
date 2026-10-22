package com.naqqa.authenticator.core;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;

public final class Hotp {

    public static final int MIN_DIGITS = 6;
    public static final int MAX_DIGITS = 10;

    private Hotp() {
    }

    public static String generate(byte[] key, long counter, int digits, HmacAlgorithm algorithm) {
        if (key == null || key.length == 0) {
            throw new IllegalArgumentException("key must not be empty");
        }
        if (digits < MIN_DIGITS || digits > MAX_DIGITS) {
            throw new IllegalArgumentException("digits must be between " + MIN_DIGITS + " and " + MAX_DIGITS);
        }
        byte[] hash = hmac(algorithm, key, ByteBuffer.allocate(Long.BYTES).putLong(counter).array());
        int offset = hash[hash.length - 1] & 0x0F;
        long binary = ((long) (hash[offset] & 0x7F) << 24)
                | ((long) (hash[offset + 1] & 0xFF) << 16)
                | ((long) (hash[offset + 2] & 0xFF) << 8)
                | (long) (hash[offset + 3] & 0xFF);
        long otp = binary % pow10(digits);
        String text = Long.toString(otp);
        StringBuilder padded = new StringBuilder(digits);
        for (int i = text.length(); i < digits; i++) {
            padded.append('0');
        }
        return padded.append(text).toString();
    }

    private static long pow10(int exponent) {
        long value = 1;
        for (int i = 0; i < exponent; i++) {
            value *= 10;
        }
        return value;
    }

    private static byte[] hmac(HmacAlgorithm algorithm, byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance(algorithm.jcaName());
            mac.init(new SecretKeySpec(key, algorithm.jcaName()));
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC unavailable: " + algorithm.jcaName(), e);
        }
    }
}
