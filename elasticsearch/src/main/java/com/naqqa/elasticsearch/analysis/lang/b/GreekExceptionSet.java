package com.naqqa.elasticsearch.analysis.lang.b;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class GreekExceptionSet {

    private final Set<String> words;
    private final boolean ignoreCase;

    GreekExceptionSet(List<String> words, boolean ignoreCase) {
        this.ignoreCase = ignoreCase;
        this.words = new HashSet<>();
        for (String w : words) {
            this.words.add(ignoreCase ? w.toLowerCase(Locale.ROOT) : w);
        }
    }

    boolean contains(char[] s, int off, int len) {
        String key = new String(s, off, len);
        if (ignoreCase) {
            key = key.toLowerCase(Locale.ROOT);
        }
        return words.contains(key);
    }
}
