package com.naqqa.authenticator.core;

import java.io.ByteArrayOutputStream;
import java.util.Locale;

public final class Base32 {

    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private static final int[] LOOKUP = new int[128];

    static {
        java.util.Arrays.fill(LOOKUP, -1);
        for (int i = 0; i < ALPHABET.length; i++) {
            LOOKUP[ALPHABET[i]] = i;
        }
    }

    private Base32() {
    }

    public static String encode(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        StringBuilder out = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                out.append(ALPHABET[(buffer >>> (bits - 5)) & 0x1F]);
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(ALPHABET[(buffer << (5 - bits)) & 0x1F]);
        }
        return out.toString();
    }

    public static byte[] decode(String text) {
        if (text == null) {
            throw new IllegalArgumentException("base32 input is null");
        }
        String clean = text.replace(" ", "").replace("-", "").toUpperCase(Locale.ROOT);
        int end = clean.length();
        while (end > 0 && clean.charAt(end - 1) == '=') {
            end--;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(end * 5 / 8);
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < end; i++) {
            char c = clean.charAt(i);
            int value = c < 128 ? LOOKUP[c] : -1;
            if (value < 0) {
                throw new IllegalArgumentException("invalid base32 character");
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >>> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}
