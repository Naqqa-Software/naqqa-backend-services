package com.naqqa.elasticsearch.analysis.lang.c;

import com.naqqa.elasticsearch.analysis.stem.Stemmer;

public final class SoraniNormalizer implements Stemmer {

    public static final char YEH = 'ي';
    public static final char DOTLESS_YEH = 'ى';
    public static final char FARSI_YEH = 'ی';
    public static final char KAF = 'ك';
    public static final char KEHEH = 'ک';
    public static final char HEH = 'ه';
    public static final char HEH_YEH = 'ۀ';
    public static final char HEH_GOAL = 'ہ';
    public static final char TEH_MARBUTA = 'ة';
    public static final char AE = 'ە';
    public static final char ZWNJ = '‌';

    @Override
    public boolean stem(StringBuilder word) {
        boolean changed = false;
        for (int i = word.length() - 2; i >= 0; i--) {
            if (word.charAt(i) == HEH && word.charAt(i + 1) == ZWNJ) {
                word.setCharAt(i, AE);
                word.deleteCharAt(i + 1);
                changed = true;
            }
        }
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (c == YEH || c == DOTLESS_YEH) {
                word.setCharAt(i, FARSI_YEH);
                changed = true;
            } else if (c == KAF) {
                word.setCharAt(i, KEHEH);
                changed = true;
            } else if (c == HEH_YEH || c == HEH_GOAL) {
                word.setCharAt(i, HEH);
                changed = true;
            } else if (c == TEH_MARBUTA) {
                word.setCharAt(i, AE);
                changed = true;
            }
        }
        for (int i = word.length() - 1; i >= 0; i--) {
            if (word.charAt(i) == ZWNJ) {
                word.deleteCharAt(i);
                changed = true;
            }
        }
        return changed;
    }
}
