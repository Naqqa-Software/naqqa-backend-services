package com.naqqa.elasticsearch.analysis.stem.light;

public final class DutchLightStemmer extends CharArrayStemmer {

    public DutchLightStemmer() {
    }

    @Override
    public int stem(char[] s, int len) {
        for (int i = 0; i < len; i++) {
            switch (s[i]) {
                case 'ä' -> s[i] = 'a';
                case 'ë' -> s[i] = 'e';
                case 'ï' -> s[i] = 'i';
                case 'ö' -> s[i] = 'o';
                case 'ü' -> s[i] = 'u';
                default -> {
                }
            }
        }
        len = stepSuffix(s, len);
        return undouble(s, len);
    }

    private static int stepSuffix(char[] s, int len) {
        if (len > 7 && hasSuffix(s, len, "heden")) {
            System.arraycopy("heid".toCharArray(), 0, s, len - 5, 4);
            return len - 1;
        }
        if (len > 6 && (hasSuffix(s, len, "eren") || hasSuffix(s, len, "ende"))) {
            return len - 4;
        }
        if (len > 5 && (hasSuffix(s, len, "sen") || hasSuffix(s, len, "ten") || hasSuffix(s, len, "den")
            || hasSuffix(s, len, "tje"))) {
            return len - 3;
        }
        if (len > 4 && (hasSuffix(s, len, "en") || hasSuffix(s, len, "se") || hasSuffix(s, len, "de")
            || hasSuffix(s, len, "te") || hasSuffix(s, len, "je"))) {
            return len - 2;
        }
        if (len > 3 && (s[len - 1] == 's' || s[len - 1] == 'e')) {
            return len - 1;
        }
        return len;
    }

    private static int undouble(char[] s, int len) {
        if (len > 4 && s[len - 1] == s[len - 2]) {
            switch (s[len - 1]) {
                case 'k', 'l', 'n', 'p', 't' -> {
                    return len - 1;
                }
                default -> {
                }
            }
        }
        return len;
    }

    private static boolean hasSuffix(char[] s, int len, String suffix) {
        int n = suffix.length();
        if (n > len) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            if (s[len - n + i] != suffix.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}
