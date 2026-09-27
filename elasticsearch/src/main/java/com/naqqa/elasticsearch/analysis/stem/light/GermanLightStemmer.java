package com.naqqa.elasticsearch.analysis.stem.light;

public final class GermanLightStemmer extends CharArrayStemmer {

    public GermanLightStemmer() {
    }

    @Override
    public int stem(char[] s, int len) {
        for (int i = 0; i < len; i++) {
            switch (s[i]) {
                case 'ä', 'à', 'á', 'â' -> s[i] = 'a';
                case 'ö', 'ò', 'ó', 'ô' -> s[i] = 'o';
                case 'ï', 'ì', 'í', 'î' -> s[i] = 'i';
                case 'ü', 'ù', 'ú', 'û' -> s[i] = 'u';
                default -> {
                }
            }
        }
        len = step1(s, len);
        return step2(s, len);
    }

    private static boolean stEnding(char ch) {
        return switch (ch) {
            case 'b', 'd', 'f', 'g', 'h', 'k', 'l', 'm', 'n', 't' -> true;
            default -> false;
        };
    }

    private static int step1(char[] s, int len) {
        if (len > 5 && s[len - 3] == 'e' && s[len - 2] == 'r' && s[len - 1] == 'n') {
            return len - 3;
        }
        if (len > 4 && s[len - 2] == 'e') {
            switch (s[len - 1]) {
                case 'm', 'n', 'r', 's' -> {
                    return len - 2;
                }
                default -> {
                }
            }
        }
        if (len > 3 && s[len - 1] == 'e') {
            return len - 1;
        }
        if (len > 3 && s[len - 1] == 's' && stEnding(s[len - 2])) {
            return len - 1;
        }
        return len;
    }

    private static int step2(char[] s, int len) {
        if (len > 5 && s[len - 3] == 'e' && s[len - 2] == 's' && s[len - 1] == 't') {
            return len - 3;
        }
        if (len > 4 && s[len - 2] == 'e' && (s[len - 1] == 'r' || s[len - 1] == 'n')) {
            return len - 2;
        }
        if (len > 4 && s[len - 2] == 's' && s[len - 1] == 't' && stEnding(s[len - 3])) {
            return len - 2;
        }
        return len;
    }
}
