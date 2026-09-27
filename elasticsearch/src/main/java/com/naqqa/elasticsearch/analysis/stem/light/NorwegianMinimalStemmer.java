package com.naqqa.elasticsearch.analysis.stem.light;

public final class NorwegianMinimalStemmer extends CharArrayStemmer {

    private final boolean useNynorsk;

    public NorwegianMinimalStemmer() {
        this(true, false);
    }

    public NorwegianMinimalStemmer(boolean nynorsk) {
        this(!nynorsk, nynorsk);
    }

    public NorwegianMinimalStemmer(boolean bokmaal, boolean nynorsk) {
        if (!bokmaal && !nynorsk) {
            throw new IllegalArgumentException("at least one of bokmaal or nynorsk must be enabled");
        }
        this.useNynorsk = nynorsk;
    }

    @Override
    public int stem(char[] s, int len) {
        if (len > 4 && s[len - 1] == 's') {
            len--;
        }
        if (len > 5 && (endsWith(s, len, "ene") || (endsWith(s, len, "ane") && useNynorsk))) {
            return len - 3;
        }
        if (len > 4
            && (endsWith(s, len, "er")
                || endsWith(s, len, "en")
                || endsWith(s, len, "et")
                || (endsWith(s, len, "ar") && useNynorsk))) {
            return len - 2;
        }
        if (len > 3) {
            switch (s[len - 1]) {
                case 'a', 'e' -> {
                    return len - 1;
                }
                default -> {
                }
            }
        }
        return len;
    }
}
