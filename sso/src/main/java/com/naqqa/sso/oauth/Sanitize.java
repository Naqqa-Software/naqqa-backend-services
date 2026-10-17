package com.naqqa.sso.oauth;

import java.util.Locale;
import java.util.regex.Pattern;

final class Sanitize {
    private static final Pattern EMAIL = Pattern.compile("[^\\s@<>\"',;()\\\\\\[\\]]{1,64}@[a-z0-9]([a-z0-9.\\-]{0,251}[a-z0-9])?");

    private Sanitize() {
    }

    static String text(Object value, int max) {
        if (!(value instanceof String)) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        String s = (String) value;
        for (int i = 0; i < s.length() && sb.length() < max; i++) {
            char c = s.charAt(i);
            if (!Character.isISOControl(c) && c != ' ' && c != ' ') {
                sb.append(c);
            }
        }
        String out = sb.toString().strip();
        return out.isEmpty() ? null : out;
    }

    static String email(Object value) {
        String s = text(value, 254);
        if (s == null) {
            return null;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0x7e) {
                return null;
            }
        }
        s = s.toLowerCase(Locale.ROOT);
        return EMAIL.matcher(s).matches() ? s : null;
    }

    static String pictureUrl(Object value) {
        String s = text(value, 1024);
        if (s == null || !s.regionMatches(true, 0, "https://", 0, 8) || s.indexOf(' ') >= 0) {
            return null;
        }
        return s;
    }

    static boolean truthy(Object value) {
        return Boolean.TRUE.equals(value) || (value instanceof String && ((String) value).equalsIgnoreCase("true"));
    }
}
