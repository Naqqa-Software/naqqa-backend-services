package com.naqqa.elasticsearch.analysis.lang.c;

import com.naqqa.elasticsearch.analysis.stem.Stemmer;

abstract class SuffixStemmer implements Stemmer {

    protected abstract String[] suffixes();

    protected int minRemaining() {
        return 2;
    }

    @Override
    public boolean stem(StringBuilder word) {
        for (String suffix : suffixes()) {
            int sufLen = suffix.length();
            int wordLen = word.length();
            if (wordLen - sufLen < minRemaining()) {
                continue;
            }
            if (endsWith(word, suffix)) {
                word.setLength(wordLen - sufLen);
                return true;
            }
        }
        return false;
    }

    private static boolean endsWith(StringBuilder word, String suffix) {
        int wl = word.length();
        int sl = suffix.length();
        if (sl > wl) {
            return false;
        }
        for (int i = 0; i < sl; i++) {
            if (word.charAt(wl - sl + i) != suffix.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}
