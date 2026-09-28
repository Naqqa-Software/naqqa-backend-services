package com.naqqa.elasticsearch.analysis.lang.b;

import com.naqqa.elasticsearch.analysis.TokenFilter;
import com.naqqa.elasticsearch.analysis.TokenStream;

public final class GreekLowerCaseFilter extends TokenFilter {

    public GreekLowerCaseFilter(TokenStream input) {
        super(input);
    }

    @Override
    public boolean incrementToken() {
        if (!input.incrementToken()) {
            return false;
        }
        char[] buf = token.buffer();
        int len = token.length();
        for (int i = 0; i < len; ) {
            int cp = Character.codePointAt(buf, i, len);
            int lower = lowerCase(cp);
            int count = Character.charCount(cp);
            if (Character.charCount(lower) == count) {
                if (count == 1) {
                    buf[i] = (char) lower;
                } else {
                    buf[i] = Character.highSurrogate(lower);
                    buf[i + 1] = Character.lowSurrogate(lower);
                }
            }
            i += count;
        }
        return true;
    }

    private static int lowerCase(int codepoint) {
        return switch (codepoint) {
            case 'ς' -> 'σ';
            case 'Ά', 'ά' -> 'α';
            case 'Έ', 'έ' -> 'ε';
            case 'Ή', 'ή' -> 'η';
            case 'Ί', 'Ϊ', 'ί', 'ϊ', 'ΐ' -> 'ι';
            case 'Ύ', 'Ϋ', 'ύ', 'ϋ', 'ΰ' -> 'υ';
            case 'Ό', 'ό' -> 'ο';
            case 'Ώ', 'ώ' -> 'ω';
            case '΢' -> 'ς';
            default -> Character.toLowerCase(codepoint);
        };
    }
}
