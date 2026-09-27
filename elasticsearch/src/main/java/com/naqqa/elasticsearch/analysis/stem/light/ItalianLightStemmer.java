package com.naqqa.elasticsearch.analysis.stem.light;

public final class ItalianLightStemmer extends CharArrayStemmer {

    public ItalianLightStemmer() {
    }

    @Override
    public int stem(char[] s, int len) {
        if (len < 6) {
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
        char prev = s[len - 2];
        return switch (s[len - 1]) {
            case 'e' -> prev == 'i' || prev == 'h' ? len - 2 : len - 1;
            case 'i' -> prev == 'h' || prev == 'i' ? len - 2 : len - 1;
            case 'a', 'o' -> prev == 'i' ? len - 2 : len - 1;
            default -> len;
        };
    }
}
