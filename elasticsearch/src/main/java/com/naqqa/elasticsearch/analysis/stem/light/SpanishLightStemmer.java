package com.naqqa.elasticsearch.analysis.stem.light;

public final class SpanishLightStemmer extends CharArrayStemmer {

    public SpanishLightStemmer() {
    }

    @Override
    public int stem(char[] s, int len) {
        if (len < 5) {
            return len;
        }
        for (int i = 0; i < len; i++) {
            switch (s[i]) {
                case 'à', 'á', 'â', 'ä' -> s[i] = 'a';
                case 'ò', 'ó', 'ô', 'ö' -> s[i] = 'o';
                case 'è', 'é', 'ê', 'ë' -> s[i] = 'e';
                case 'ù', 'ú', 'û', 'ü' -> s[i] = 'u';
                case 'ì', 'í', 'î', 'ï' -> s[i] = 'i';
                default -> {
                }
            }
        }
        switch (s[len - 1]) {
            case 'o', 'a', 'e' -> {
                return len - 1;
            }
            case 's' -> {
                if (s[len - 2] == 'e' && s[len - 3] == 's' && s[len - 4] == 'e') {
                    return len - 2;
                }
                if (s[len - 2] == 'e' && s[len - 3] == 'c') {
                    s[len - 3] = 'z';
                    return len - 2;
                }
                if (s[len - 2] == 'o' || s[len - 2] == 'a' || s[len - 2] == 'e') {
                    return len - 2;
                }
                return len;
            }
            default -> {
                return len;
            }
        }
    }
}
