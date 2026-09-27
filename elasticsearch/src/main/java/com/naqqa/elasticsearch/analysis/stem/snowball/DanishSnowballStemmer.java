package com.naqqa.elasticsearch.analysis.stem.snowball;

import com.naqqa.elasticsearch.analysis.stem.Stemmer;

public final class DanishSnowballStemmer implements Stemmer {

    private static final String VOWELS = "aeiouyæåø";
    private static final String CONSONANTS = "bcdfghjklmnpqrstvwxz";
    private static final String S_ENDING = "abcdfghjklmnoprtvyzå";

    private static final String[] MAIN = {
        "hed", "ethed", "ered", "e", "erede", "ende", "erende", "ene", "erne", "ere",
        "en", "heden", "eren", "er", "heder", "erer", "heds", "es", "endes",
        "erendes", "enes", "ernes", "eres", "ens", "hedens", "erens", "ers", "ets",
        "erets", "et", "eret", "s"
    };

    private static final String[] PAIRS = {"gd", "dt", "gt", "kt"};

    private static final String[] OTHER = {"ig", "lig", "elig", "els", "løst"};

    public DanishSnowballStemmer() {
    }

    @Override
    public boolean stem(StringBuilder w) {
        int original = w.length();
        int p1 = NordicSupport.scandinavianR1(w, VOWELS);
        mainSuffix(w, p1);
        consonantPair(w, p1);
        otherSuffix(w, p1);
        undouble(w, p1);
        return w.length() != original;
    }

    private static void mainSuffix(StringBuilder w, int p1) {
        int end = w.length();
        int m = NordicSupport.longest(w, end, p1, MAIN);
        if (m < 0) {
            return;
        }
        int start = end - MAIN[m].length();
        if (MAIN[m].equals("s") && (start < 1 || !NordicSupport.in(S_ENDING, w.charAt(start - 1)))) {
            return;
        }
        w.setLength(start);
    }

    private static void consonantPair(StringBuilder w, int p1) {
        int end = w.length();
        if (end >= 1 && NordicSupport.longest(w, end, p1, PAIRS) >= 0) {
            w.setLength(end - 1);
        }
    }

    private static void otherSuffix(StringBuilder w, int p1) {
        int end = w.length();
        if (NordicSupport.endsWith(w, end, 0, "igst")) {
            w.setLength(end - 2);
            end -= 2;
        }
        int m = NordicSupport.longest(w, end, p1, OTHER);
        if (m < 0) {
            return;
        }
        if (m == 4) {
            w.setLength(end - 1);
            return;
        }
        w.setLength(end - OTHER[m].length());
        consonantPair(w, p1);
    }

    private static void undouble(StringBuilder w, int p1) {
        int end = w.length();
        if (end - 1 < p1 || end < 2) {
            return;
        }
        char c = w.charAt(end - 1);
        if (NordicSupport.in(CONSONANTS, c) && w.charAt(end - 2) == c) {
            w.setLength(end - 1);
        }
    }
}
