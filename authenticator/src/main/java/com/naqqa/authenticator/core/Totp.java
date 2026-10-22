package com.naqqa.authenticator.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.OptionalLong;

public final class Totp {

    private Totp() {
    }

    public static long counterAt(long epochMillis, int periodSeconds) {
        return Math.floorDiv(Math.floorDiv(epochMillis, 1000L), periodSeconds);
    }

    public static String generate(byte[] key, long epochMillis, TotpParameters parameters) {
        return Hotp.generate(key, counterAt(epochMillis, parameters.periodSeconds()), parameters.digits(), parameters.algorithm());
    }

    public static String normalize(String code) {
        if (code == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(code.length());
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c != ' ' && c != '-') {
                out.append(c);
            }
        }
        return out.toString();
    }

    public static OptionalLong match(byte[] key, String code, long epochMillis, TotpParameters parameters) {
        String candidate = normalize(code);
        if (candidate.length() != parameters.digits() || !isDigits(candidate)) {
            return OptionalLong.empty();
        }
        byte[] expected = candidate.getBytes(StandardCharsets.US_ASCII);
        long current = counterAt(epochMillis, parameters.periodSeconds());
        long matched = Long.MIN_VALUE;
        boolean found = false;
        for (int offset = -parameters.skewSteps(); offset <= parameters.skewSteps(); offset++) {
            long counter = current + offset;
            if (counter < 0) {
                continue;
            }
            String otp = Hotp.generate(key, counter, parameters.digits(), parameters.algorithm());
            boolean equal = MessageDigest.isEqual(otp.getBytes(StandardCharsets.US_ASCII), expected);
            if (equal && !found) {
                matched = counter;
                found = true;
            }
        }
        return found ? OptionalLong.of(matched) : OptionalLong.empty();
    }

    private static boolean isDigits(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
