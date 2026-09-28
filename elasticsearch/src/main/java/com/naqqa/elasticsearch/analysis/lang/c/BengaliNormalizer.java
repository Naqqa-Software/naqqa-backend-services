package com.naqqa.elasticsearch.analysis.lang.c;

import com.naqqa.elasticsearch.analysis.stem.Stemmer;

public final class BengaliNormalizer implements Stemmer {

    @Override
    public boolean stem(StringBuilder word) {
        boolean changed = false;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            switch (c) {
                case 'ৎ' -> { word.setCharAt(i, 'ত'); changed = true; }
                case 'ৰ' -> { word.setCharAt(i, 'র'); changed = true; }
                case 'ৱ' -> { word.setCharAt(i, 'ব'); changed = true; }
                default -> { }
            }
        }
        for (int i = word.length() - 1; i >= 0; i--) {
            char c = word.charAt(i);
            if (c == '‍' || c == '‌') {
                word.deleteCharAt(i);
                changed = true;
            }
        }
        return changed;
    }
}
