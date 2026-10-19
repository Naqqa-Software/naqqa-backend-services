package com.naqqa.elasticsearch.os;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Folding, tokenization and scoring ported from {@code search-text.ts}.
 */
public final class SearchText {

    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
    private static final Pattern SPLIT = Pattern.compile("[^\\p{L}\\p{N}]+");

    private SearchText() {
    }

    public static String foldText(Object value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(String.valueOf(value), Normalizer.Form.NFKD);
        return DIACRITICS.matcher(normalized).replaceAll("").toLowerCase();
    }

    public static List<String> tokenize(Object value) {
        String folded = foldText(value);
        List<String> out = new ArrayList<>();
        if (folded.isEmpty()) {
            return out;
        }
        for (String part : SPLIT.split(folded)) {
            if (!part.isEmpty()) {
                out.add(part);
            }
        }
        return out;
    }

    public static double scoreText(List<String> docTokens, List<String> queryTokens) {
        if (queryTokens.isEmpty()) {
            return 0;
        }
        double total = 0;
        for (String q : queryTokens) {
            int best = 0;
            for (String d : docTokens) {
                if (d.equals(q)) {
                    best = 3;
                    break;
                }
                if (d.startsWith(q)) {
                    if (best < 2) {
                        best = 2;
                    }
                } else if (d.contains(q)) {
                    if (best < 1) {
                        best = 1;
                    }
                }
            }
            if (best == 0) {
                return 0;
            }
            total += best;
        }
        return total;
    }
}
