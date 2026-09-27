package com.naqqa.elasticsearch.analysis.stem.light;

public final class SwedishLightStemmer extends CharArrayStemmer {

    public SwedishLightStemmer() {
    }

    @Override
    public int stem(char[] s, int len) {
        if (len > 4 && s[len - 1] == 's') {
            len--;
        }
        if (len > 7 && (endsWith(s, len, "elser") || endsWith(s, len, "heten"))) {
            return len - 5;
        }
        if (len > 6
            && (endsWith(s, len, "arne")
                || endsWith(s, len, "erna")
                || endsWith(s, len, "ande")
                || endsWith(s, len, "else")
                || endsWith(s, len, "aste")
                || endsWith(s, len, "orna")
                || endsWith(s, len, "aren"))) {
            return len - 4;
        }
        if (len > 5 && (endsWith(s, len, "are") || endsWith(s, len, "ast") || endsWith(s, len, "het"))) {
            return len - 3;
        }
        if (len > 4
            && (endsWith(s, len, "ar")
                || endsWith(s, len, "er")
                || endsWith(s, len, "or")
                || endsWith(s, len, "en")
                || endsWith(s, len, "at")
                || endsWith(s, len, "te")
                || endsWith(s, len, "et"))) {
            return len - 2;
        }
        if (len > 3) {
            switch (s[len - 1]) {
                case 't', 'a', 'e', 'n' -> {
                    return len - 1;
                }
                default -> {
                }
            }
        }
        return len;
    }
}
