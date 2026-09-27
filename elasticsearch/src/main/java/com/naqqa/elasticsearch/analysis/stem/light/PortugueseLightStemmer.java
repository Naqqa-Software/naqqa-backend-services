package com.naqqa.elasticsearch.analysis.stem.light;

public final class PortugueseLightStemmer extends CharArrayStemmer {

    public PortugueseLightStemmer() {
    }

    @Override
    public int stem(char[] s, int len) {
        if (len < 4) {
            return len;
        }
        len = removeSuffix(s, len);
        if (len > 3 && s[len - 1] == 'a') {
            len = normFeminine(s, len);
        }
        if (len > 4) {
            switch (s[len - 1]) {
                case 'e', 'a', 'o' -> len--;
                default -> {
                }
            }
        }
        for (int i = 0; i < len; i++) {
            switch (s[i]) {
                case 'à', 'á', 'â', 'ä', 'ã' -> s[i] = 'a';
                case 'ò', 'ó', 'ô', 'ö', 'õ' -> s[i] = 'o';
                case 'è', 'é', 'ê', 'ë' -> s[i] = 'e';
                case 'ù', 'ú', 'û', 'ü' -> s[i] = 'u';
                case 'ì', 'í', 'î', 'ï' -> s[i] = 'i';
                case 'ç' -> s[i] = 'c';
                default -> {
                }
            }
        }
        return len;
    }

    private static int removeSuffix(char[] s, int len) {
        if (len > 4 && endsWith(s, len, "es")) {
            switch (s[len - 3]) {
                case 'r', 's', 'l', 'z' -> {
                    return len - 2;
                }
                default -> {
                }
            }
        }
        if (len > 3 && endsWith(s, len, "ns")) {
            s[len - 2] = 'm';
            return len - 1;
        }
        if (len > 4 && (endsWith(s, len, "eis") || endsWith(s, len, "éis"))) {
            s[len - 3] = 'e';
            s[len - 2] = 'l';
            return len - 1;
        }
        if (len > 4 && endsWith(s, len, "ais")) {
            s[len - 2] = 'l';
            return len - 1;
        }
        if (len > 4 && endsWith(s, len, "óis")) {
            s[len - 3] = 'o';
            s[len - 2] = 'l';
            return len - 1;
        }
        if (len > 4 && endsWith(s, len, "is")) {
            s[len - 1] = 'l';
            return len;
        }
        if (len > 3 && (endsWith(s, len, "ões") || endsWith(s, len, "ães"))) {
            len--;
            s[len - 2] = 'ã';
            s[len - 1] = 'o';
            return len;
        }
        if (len > 6 && endsWith(s, len, "mente")) {
            return len - 5;
        }
        if (len > 3 && s[len - 1] == 's') {
            return len - 1;
        }
        return len;
    }

    private static int normFeminine(char[] s, int len) {
        if (len > 7 && (endsWith(s, len, "inha") || endsWith(s, len, "iaca") || endsWith(s, len, "eira"))) {
            s[len - 1] = 'o';
            return len;
        }
        if (len > 6) {
            if (endsWith(s, len, "osa")
                || endsWith(s, len, "ica")
                || endsWith(s, len, "ida")
                || endsWith(s, len, "ada")
                || endsWith(s, len, "iva")
                || endsWith(s, len, "ama")) {
                s[len - 1] = 'o';
                return len;
            }
            if (endsWith(s, len, "ona")) {
                s[len - 3] = 'ã';
                s[len - 2] = 'o';
                return len - 1;
            }
            if (endsWith(s, len, "ora")) {
                return len - 1;
            }
            if (endsWith(s, len, "esa")) {
                s[len - 3] = 'ê';
                return len - 1;
            }
            if (endsWith(s, len, "na")) {
                s[len - 1] = 'o';
                return len;
            }
        }
        return len;
    }
}
