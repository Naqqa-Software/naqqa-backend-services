package com.naqqa.elasticsearch.analysis.stem.light;

import com.naqqa.elasticsearch.analysis.stem.Stemmer;

public final class GermanNormalizer implements Stemmer {

    private static final int N = 0;
    private static final int V = 1;
    private static final int U = 2;

    public GermanNormalizer() {
    }

    @Override
    public boolean stem(StringBuilder word) {
        boolean changed = false;
        int state = N;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            switch (c) {
                case 'a', 'o' -> state = U;
                case 'u' -> state = state == N ? U : V;
                case 'e' -> {
                    if (state == U) {
                        word.deleteCharAt(i--);
                        changed = true;
                    }
                    state = V;
                }
                case 'i', 'q', 'y' -> state = V;
                case 'ä' -> {
                    word.setCharAt(i, 'a');
                    changed = true;
                    state = V;
                }
                case 'ö' -> {
                    word.setCharAt(i, 'o');
                    changed = true;
                    state = V;
                }
                case 'ü' -> {
                    word.setCharAt(i, 'u');
                    changed = true;
                    state = V;
                }
                case 'ß' -> {
                    word.setCharAt(i, 's');
                    word.insert(++i, 's');
                    changed = true;
                    state = N;
                }
                default -> state = N;
            }
        }
        return changed;
    }
}
