package com.naqqa.elasticsearch.analysis.lang.c;

import com.naqqa.elasticsearch.analysis.stem.Stemmer;

public final class PersianNormalizer implements Stemmer {

    public static final char YEH = 'ي';
    public static final char DOTLESS_YEH = 'ى';
    public static final char FARSI_YEH = 'ی';
    public static final char YEH_BARREE = 'ے';
    public static final char KEHEH = 'ک';
    public static final char KAF = 'ك';
    public static final char HAMZA_ABOVE = 'ٔ';
    public static final char HEH_YEH = 'ۀ';
    public static final char HEH_GOAL = 'ہ';
    public static final char HEH = 'ه';
    public static final char ZWNJ = '‌';

    @Override
    public boolean stem(StringBuilder word) {
        boolean changed = false;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (c == FARSI_YEH || c == YEH_BARREE || c == DOTLESS_YEH) {
                word.setCharAt(i, YEH);
                changed = true;
            } else if (c == KEHEH) {
                word.setCharAt(i, KAF);
                changed = true;
            } else if (c == HEH_YEH || c == HEH_GOAL) {
                word.setCharAt(i, HEH);
                changed = true;
            }
        }
        for (int i = word.length() - 1; i >= 0; i--) {
            char c = word.charAt(i);
            if (c == HAMZA_ABOVE || c == ZWNJ) {
                word.deleteCharAt(i);
                changed = true;
            }
        }
        return changed;
    }
}
