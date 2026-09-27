package com.naqqa.elasticsearch.analysis.stem.light;

public final class NorwegianLightStemmer extends CharArrayStemmer {

    private final boolean useBokmaal;
    private final boolean useNynorsk;

    public NorwegianLightStemmer() {
        this(true, false);
    }

    public NorwegianLightStemmer(boolean nynorsk) {
        this(!nynorsk, nynorsk);
    }

    public NorwegianLightStemmer(boolean bokmaal, boolean nynorsk) {
        if (!bokmaal && !nynorsk) {
            throw new IllegalArgumentException("at least one of bokmaal or nynorsk must be enabled");
        }
        this.useBokmaal = bokmaal;
        this.useNynorsk = nynorsk;
    }

    @Override
    public int stem(char[] s, int len) {
        if (len > 4 && s[len - 1] == 's') {
            len--;
        }
        if (len > 7
            && ((endsWith(s, len, "heter") && useBokmaal)
                || (endsWith(s, len, "heten") && useBokmaal)
                || (endsWith(s, len, "heita") && useNynorsk))) {
            return len - 5;
        }
        if (len > 8
            && useNynorsk
            && (endsWith(s, len, "heiter") || endsWith(s, len, "leiken") || endsWith(s, len, "leikar"))) {
            return len - 6;
        }
        if (len > 5 && (endsWith(s, len, "dom") || (endsWith(s, len, "het") && useBokmaal))) {
            return len - 3;
        }
        if (len > 6
            && useNynorsk
            && (endsWith(s, len, "heit") || endsWith(s, len, "semd") || endsWith(s, len, "leik"))) {
            return len - 4;
        }
        if (len > 7 && (endsWith(s, len, "elser") || endsWith(s, len, "elsen"))) {
            return len - 5;
        }
        if (len > 6
            && ((endsWith(s, len, "ende") && useBokmaal)
                || (endsWith(s, len, "ande") && useNynorsk)
                || endsWith(s, len, "else")
                || (endsWith(s, len, "este") && useBokmaal)
                || (endsWith(s, len, "aste") && useNynorsk)
                || (endsWith(s, len, "eren") && useBokmaal)
                || (endsWith(s, len, "aren") && useNynorsk))) {
            return len - 4;
        }
        if (len > 5
            && ((endsWith(s, len, "ere") && useBokmaal)
                || (endsWith(s, len, "are") && useNynorsk)
                || (endsWith(s, len, "est") && useBokmaal)
                || (endsWith(s, len, "ast") && useNynorsk)
                || endsWith(s, len, "ene")
                || (endsWith(s, len, "ane") && useNynorsk))) {
            return len - 3;
        }
        if (len > 4
            && (endsWith(s, len, "er")
                || endsWith(s, len, "en")
                || endsWith(s, len, "et")
                || (endsWith(s, len, "ar") && useNynorsk)
                || (endsWith(s, len, "st") && useBokmaal)
                || endsWith(s, len, "te"))) {
            return len - 2;
        }
        if (len > 3) {
            switch (s[len - 1]) {
                case 'a', 'e', 'n' -> {
                    return len - 1;
                }
                default -> {
                }
            }
        }
        return len;
    }
}
