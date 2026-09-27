package com.naqqa.elasticsearch.analysis.stem.light;

public final class RussianLightStemmer extends CharArrayStemmer {

    private static final String[] FOUR = {"иями", "оями"};

    private static final String[] THREE = {
        "иям", "иях", "оях", "ями", "оям", "оьв", "ами", "его", "ему", "ери", "ими", "ого", "ому", "ыми", "оев"
    };

    private static final String[] TWO = {
        "ая", "яя", "ях", "юю", "ах", "ею", "их", "ия", "ию", "ьв", "ою", "ую", "ям", "ых", "ея", "ам", "ем",
        "ей", "ём", "ев", "ий", "им", "ое", "ой", "ом", "ов", "ые", "ый", "ым", "ми"
    };

    public RussianLightStemmer() {
    }

    @Override
    public int stem(char[] s, int len) {
        len = removeCase(s, len);
        return normalize(s, len);
    }

    private static int normalize(char[] s, int len) {
        if (len > 3) {
            switch (s[len - 1]) {
                case 'ь', 'и' -> {
                    return len - 1;
                }
                case 'н' -> {
                    if (s[len - 2] == 'н') {
                        return len - 1;
                    }
                }
                default -> {
                }
            }
        }
        return len;
    }

    private static boolean endsWithAny(char[] s, int len, String[] suffixes) {
        for (String suffix : suffixes) {
            if (endsWith(s, len, suffix)) {
                return true;
            }
        }
        return false;
    }

    private static int removeCase(char[] s, int len) {
        if (len > 6 && endsWithAny(s, len, FOUR)) {
            return len - 4;
        }
        if (len > 5 && endsWithAny(s, len, THREE)) {
            return len - 3;
        }
        if (len > 4 && endsWithAny(s, len, TWO)) {
            return len - 2;
        }
        if (len > 3) {
            switch (s[len - 1]) {
                case 'а', 'е', 'и', 'о', 'у', 'й', 'ы', 'я', 'ь' -> {
                    return len - 1;
                }
                default -> {
                }
            }
        }
        return len;
    }
}
