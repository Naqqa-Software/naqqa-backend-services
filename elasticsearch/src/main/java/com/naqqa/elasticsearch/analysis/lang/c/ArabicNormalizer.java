package com.naqqa.elasticsearch.analysis.lang.c;

import com.naqqa.elasticsearch.analysis.stem.Stemmer;

public final class ArabicNormalizer implements Stemmer {

    public static final char ALEF = 'ا';
    public static final char ALEF_MADDA = 'آ';
    public static final char ALEF_HAMZA_ABOVE = 'أ';
    public static final char ALEF_HAMZA_BELOW = 'إ';
    public static final char YEH = 'ي';
    public static final char DOTLESS_YEH = 'ى';
    public static final char TEH_MARBUTA = 'ة';
    public static final char HEH = 'ه';
    public static final char TATWEEL = 'ـ';
    public static final char FATHATAN = 'ً';
    public static final char DAMMATAN = 'ٌ';
    public static final char KASRATAN = 'ٍ';
    public static final char FATHA = 'َ';
    public static final char DAMMA = 'ُ';
    public static final char KASRA = 'ِ';
    public static final char SHADDA = 'ّ';
    public static final char SUKUN = 'ْ';

    @Override
    public boolean stem(StringBuilder word) {
        boolean changed = false;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (c == ALEF_MADDA || c == ALEF_HAMZA_ABOVE || c == ALEF_HAMZA_BELOW) {
                word.setCharAt(i, ALEF);
                changed = true;
            } else if (c == DOTLESS_YEH) {
                word.setCharAt(i, YEH);
                changed = true;
            } else if (c == TEH_MARBUTA) {
                word.setCharAt(i, HEH);
                changed = true;
            }
        }
        for (int i = word.length() - 1; i >= 0; i--) {
            char c = word.charAt(i);
            if (c == TATWEEL || c == FATHATAN || c == DAMMATAN || c == KASRATAN
                || c == FATHA || c == DAMMA || c == KASRA || c == SHADDA || c == SUKUN) {
                word.deleteCharAt(i);
                changed = true;
            }
        }
        return changed;
    }
}
