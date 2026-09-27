package com.naqqa.elasticsearch.security.support;

import java.util.List;

public final class WildcardMatcher {

    private WildcardMatcher() {
    }

    public static boolean matches(String pattern, String value) {
        if (pattern == null || value == null) {
            return false;
        }
        if (pattern.length() >= 2 && pattern.startsWith("/") && pattern.endsWith("/")) {
            return value.matches(pattern.substring(1, pattern.length() - 1));
        }
        return value.matches(toRegex(pattern));
    }

    public static boolean matchesAny(List<String> patterns, String value) {
        if (patterns == null) {
            return false;
        }
        for (String pattern : patterns) {
            if (matches(pattern, value)) {
                return true;
            }
        }
        return false;
    }

    private static String toRegex(String pattern) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                case '.', '(', ')', '[', ']', '{', '}', '+', '^', '$', '|', '\\' -> sb.append('\\').append(c);
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
