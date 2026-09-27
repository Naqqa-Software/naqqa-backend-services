package com.naqqa.elasticsearch.analysis.stem.light;

public final class GermanMinimalStemmer extends CharArrayStemmer {

    public GermanMinimalStemmer() {
    }

    @Override
    public int stem(char[] s, int len) {
        if (len < 5) {
            return len;
        }
        for (int i = 0; i < len; i++) {
            switch (s[i]) {
                case 'ä' -> s[i] = 'a';
                case 'ö' -> s[i] = 'o';
                case 'ü' -> s[i] = 'u';
                default -> {
                }
            }
        }
        if (len > 6 && s[len - 3] == 'n' && s[len - 2] == 'e' && s[len - 1] == 'n') {
            return len - 3;
        }
        if (len > 5) {
            char last = s[len - 1];
            char prev = s[len - 2];
            if ((last == 'n' || last == 's' || last == 'r') && prev == 'e') {
                return len - 2;
            }
            if (last == 'e' && prev == 's') {
                return len - 2;
            }
        }
        return switch (s[len - 1]) {
            case 'n', 'e', 's', 'r' -> len - 1;
            default -> len;
        };
    }
}
