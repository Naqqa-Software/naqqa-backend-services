package com.naqqa.elasticsearch.analysis.stem.snowball;

import com.naqqa.elasticsearch.analysis.stem.Stemmer;

public final class NorwegianSnowballStemmer implements Stemmer {

    private static final String VOWELS = "aeiouyæåø";
    private static final String S_ENDING = "bcdfghjlmnoprtvyz";

    private static final String[] MAIN = {
        "a", "e", "ede", "ande", "ende", "ane", "ene", "hetene", "en", "heten", "ar",
        "er", "heter", "as", "es", "edes", "endes", "enes", "hetenes", "ens",
        "hetens", "ers", "ets", "et", "het", "ast", "s", "erte", "ert"
    };

    private static final String[] PAIRS = {"dt", "vt"};

    private static final String[] OTHER = {
        "leg", "eleg", "ig", "eig", "lig", "elig", "els", "lov", "elov", "slov", "hetslov"
    };

    public NorwegianSnowballStemmer() {
    }

    @Override
    public boolean stem(StringBuilder w) {
        int original = w.length();
        int p1 = NordicSupport.scandinavianR1(w, VOWELS);
        mainSuffix(w, p1);
        int end = w.length();
        if (NordicSupport.longest(w, end, p1, PAIRS) >= 0) {
            w.setLength(end - 1);
        }
        end = w.length();
        int m = NordicSupport.longest(w, end, p1, OTHER);
        if (m >= 0) {
            w.setLength(end - OTHER[m].length());
        }
        return w.length() != original;
    }

    private static void mainSuffix(StringBuilder w, int p1) {
        int end = w.length();
        int m = NordicSupport.longest(w, end, p1, MAIN);
        if (m < 0) {
            return;
        }
        String suffix = MAIN[m];
        int start = end - suffix.length();
        switch (suffix) {
            case "s" -> {
                if (start < 1) {
                    return;
                }
                char c = w.charAt(start - 1);
                if (NordicSupport.in(S_ENDING, c)
                    || (c == 'k' && start >= 2 && !NordicSupport.in(VOWELS, w.charAt(start - 2)))) {
                    w.setLength(start);
                }
            }
            case "erte", "ert" -> w.setLength(start + 2);
            default -> w.setLength(start);
        }
    }
}
