package com.naqqa.elasticsearch.analysis.lang.c;

import com.naqqa.elasticsearch.analysis.stem.Stemmer;

public final class IndicNormalizer implements Stemmer {

    private static final char DEVANAGARI_NUKTA = '़';
    private static final char BENGALI_NUKTA = '়';

    @Override
    public boolean stem(StringBuilder word) {
        boolean changed = false;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            char base = 0;
            char nukta = 0;
            switch (c) {
                case 'ऩ' -> { base = 'न'; nukta = DEVANAGARI_NUKTA; }
                case 'ऱ' -> { base = 'र'; nukta = DEVANAGARI_NUKTA; }
                case 'ऴ' -> { base = 'ळ'; nukta = DEVANAGARI_NUKTA; }
                case 'क़' -> { base = 'क'; nukta = DEVANAGARI_NUKTA; }
                case 'ख़' -> { base = 'ख'; nukta = DEVANAGARI_NUKTA; }
                case 'ग़' -> { base = 'ग'; nukta = DEVANAGARI_NUKTA; }
                case 'ज़' -> { base = 'ज'; nukta = DEVANAGARI_NUKTA; }
                case 'ड़' -> { base = 'ड'; nukta = DEVANAGARI_NUKTA; }
                case 'ढ़' -> { base = 'ढ'; nukta = DEVANAGARI_NUKTA; }
                case 'फ़' -> { base = 'फ'; nukta = DEVANAGARI_NUKTA; }
                case 'य़' -> { base = 'य'; nukta = DEVANAGARI_NUKTA; }
                case 'ড়' -> { base = 'ড'; nukta = BENGALI_NUKTA; }
                case 'ঢ়' -> { base = 'ঢ'; nukta = BENGALI_NUKTA; }
                case 'য়' -> { base = 'য'; nukta = BENGALI_NUKTA; }
                default -> { }
            }
            if (base != 0) {
                word.setCharAt(i, base);
                word.insert(i + 1, nukta);
                i++;
                changed = true;
            }
        }
        return changed;
    }
}
