package com.naqqa.sso.mfa;

import com.naqqa.sso.SsoException;

public final class Base32 {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int[] LOOKUP = new int[128];

    static {
        java.util.Arrays.fill(LOOKUP, -1);
        for (int i = 0; i < ALPHABET.length(); i++) {
            LOOKUP[ALPHABET.charAt(i)] = i;
        }
    }

    private Base32() {
    }

    public static String encode(byte[] data, boolean padding) {
        StringBuilder sb = new StringBuilder((data.length * 8 + 4) / 5 + 8);
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                sb.append(ALPHABET.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
            buffer &= (1 << bits) - 1;
        }
        if (bits > 0) {
            sb.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        }
        if (padding) {
            while (sb.length() % 8 != 0) {
                sb.append('=');
            }
        }
        return sb.toString();
    }

    public static String encode(byte[] data) {
        return encode(data, false);
    }

    public static byte[] decode(String text) {
        if (text == null) {
            throw new SsoException("base32_invalid");
        }
        int end = text.length();
        int pad = 0;
        while (end > 0 && text.charAt(end - 1) == '=') {
            end--;
            pad++;
        }
        if (pad > 0 && text.length() % 8 != 0) {
            throw new SsoException("base32_invalid");
        }
        if (pad > 6) {
            throw new SsoException("base32_invalid");
        }
        int dataLen = end;
        int rem = dataLen % 8;
        if (rem == 1 || rem == 3 || rem == 6) {
            throw new SsoException("base32_invalid");
        }
        if (pad > 0) {
            int expectedPad = switch (rem) {
                case 2 -> 6;
                case 4 -> 4;
                case 5 -> 3;
                case 7 -> 1;
                default -> 0;
            };
            if (expectedPad != pad) {
                throw new SsoException("base32_invalid");
            }
        }
        byte[] out = new byte[dataLen * 5 / 8];
        int buffer = 0;
        int bits = 0;
        int idx = 0;
        for (int i = 0; i < dataLen; i++) {
            char c = text.charAt(i);
            int v = c < 128 ? LOOKUP[c] : -1;
            if (v < 0) {
                throw new SsoException("base32_invalid");
            }
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out[idx++] = (byte) ((buffer >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
            buffer &= (1 << bits) - 1;
        }
        if (buffer != 0) {
            throw new SsoException("base32_invalid");
        }
        return out;
    }
}
