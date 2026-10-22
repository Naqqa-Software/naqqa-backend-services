package com.naqqa.sso.tokens;

import com.naqqa.sso.util.Bytes;

final class Base62 {
    static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private Base62() {
    }

    static String random(int length) {
        StringBuilder sb = new StringBuilder(length);
        while (sb.length() < length) {
            for (byte b : Bytes.random(length * 2)) {
                int v = b & 0x3F;
                if (v < 62 && sb.length() < length) {
                    sb.append(ALPHABET.charAt(v));
                }
            }
        }
        return sb.toString();
    }

    static String encodeFixed(long value, int width) {
        char[] out = new char[width];
        long v = value;
        for (int i = width - 1; i >= 0; i--) {
            out[i] = ALPHABET.charAt((int) (v % 62));
            v /= 62;
        }
        return new String(out);
    }

    static boolean isBase62(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))) {
                return false;
            }
        }
        return true;
    }
}
