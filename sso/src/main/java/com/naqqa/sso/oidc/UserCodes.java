package com.naqqa.sso.oidc;

import com.naqqa.sso.util.Bytes;

public final class UserCodes {
    public static final String ALPHABET = "BCDFGHJKLMNPQRSTVWXZ";
    public static final int LENGTH = 8;

    private UserCodes() {
    }

    public static String generate() {
        StringBuilder sb = new StringBuilder(LENGTH);
        while (sb.length() < LENGTH) {
            for (byte b : Bytes.random(16)) {
                int v = b & 0xFF;
                if (v < 240 && sb.length() < LENGTH) {
                    sb.append(ALPHABET.charAt(v % 20));
                }
            }
        }
        return sb.toString();
    }

    public static String format(String code) {
        return code.substring(0, 4) + "-" + code.substring(4);
    }

    public static String normalize(String input) {
        if (input == null || input.length() > 32) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '-' || c == ' ') {
                continue;
            }
            char u = Character.toUpperCase(c);
            if (ALPHABET.indexOf(u) < 0 || c > 0x7E) {
                return null;
            }
            sb.append(u);
        }
        return sb.length() == LENGTH ? sb.toString() : null;
    }
}
