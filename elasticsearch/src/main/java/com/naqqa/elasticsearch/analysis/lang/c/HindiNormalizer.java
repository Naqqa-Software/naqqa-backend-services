package com.naqqa.elasticsearch.analysis.lang.c;

import com.naqqa.elasticsearch.analysis.stem.Stemmer;

public final class HindiNormalizer implements Stemmer {

    @Override
    public boolean stem(StringBuilder word) {
        boolean changed = false;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            switch (c) {
                case 'ॅ', 'ॆ' -> { word.setCharAt(i, 'े'); changed = true; }
                case 'ॉ', 'ॊ' -> { word.setCharAt(i, 'ो'); changed = true; }
                case 'ॲ' -> { word.setCharAt(i, 'अ'); changed = true; }
                default -> { }
            }
        }
        changed |= removeAll(word, '़');
        changed |= removeAll(word, '‍');
        changed |= removeAll(word, '‌');
        while (word.length() > 0 && isTrailing(word.charAt(word.length() - 1))) {
            word.deleteCharAt(word.length() - 1);
            changed = true;
        }
        return changed;
    }

    private static boolean isTrailing(char c) {
        return c == '्' || c == '।' || c == '॥';
    }

    private static boolean removeAll(StringBuilder word, char target) {
        boolean changed = false;
        for (int i = word.length() - 1; i >= 0; i--) {
            if (word.charAt(i) == target) {
                word.deleteCharAt(i);
                changed = true;
            }
        }
        return changed;
    }
}
