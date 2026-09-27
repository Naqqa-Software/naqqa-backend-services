package com.naqqa.elasticsearch.analysis.stem.snowball;

import com.naqqa.elasticsearch.analysis.stem.Stemmer;

public final class SwedishSnowballStemmer implements Stemmer {

    private static final String VOWELS = "aeiouyäåö";
    private static final String S_ENDING = "bcdfghjklmnoprtvy";
    private static final String OST_ENDING = "iklnprtuv";

    private static final String[] MAIN = {
        "a", "arna", "erna", "heterna", "orna", "ad", "e", "ade", "ande", "arne",
        "are", "aste", "en", "anden", "aren", "heten", "ern", "ar", "er", "heter",
        "or", "as", "arnas", "ernas", "ornas", "es", "ades", "andes", "ens", "arens",
        "hetens", "erns", "at", "andet", "het", "ast", "s"
    };

    private static final String[] PAIRS = {"dd", "gd", "nn", "dt", "gt", "kt", "tt"};

    private static final String[] OTHER = {"ig", "lig", "els", "fullt", "öst"};

    public SwedishSnowballStemmer() {
    }

    @Override
    public boolean stem(StringBuilder w) {
        int original = w.length();
        int p1 = NordicSupport.scandinavianR1(w, VOWELS);
        mainSuffix(w, p1);
        consonantPair(w, p1);
        otherSuffix(w, p1);
        return w.length() != original;
    }

    private static void mainSuffix(StringBuilder w, int p1) {
        int end = w.length();
        int m = NordicSupport.longest(w, end, p1, MAIN);
        if (m < 0) {
            return;
        }
        int start = end - MAIN[m].length();
        if (MAIN[m].equals("s")) {
            if (start < 1 || !NordicSupport.in(S_ENDING, w.charAt(start - 1))) {
                return;
            }
        }
        w.setLength(start);
    }

    private static void consonantPair(StringBuilder w, int p1) {
        int end = w.length();
        if (NordicSupport.longest(w, end, p1, PAIRS) >= 0) {
            w.setLength(end - 1);
        }
    }

    private static void otherSuffix(StringBuilder w, int p1) {
        int end = w.length();
        int m = NordicSupport.longest(w, end, p1, OTHER);
        if (m < 0) {
            return;
        }
        int start = end - OTHER[m].length();
        switch (m) {
            case 0, 1, 2 -> w.setLength(start);
            case 3 -> w.setLength(end - 1);
            default -> {
                if (start >= 1 && NordicSupport.in(OST_ENDING, w.charAt(start - 1))) {
                    w.setLength(end - 1);
                }
            }
        }
    }
}
