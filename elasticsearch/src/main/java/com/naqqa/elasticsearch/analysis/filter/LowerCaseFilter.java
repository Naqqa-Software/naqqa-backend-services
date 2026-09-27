package com.naqqa.elasticsearch.analysis.filter;

import com.naqqa.elasticsearch.analysis.TokenFilter;
import com.naqqa.elasticsearch.analysis.TokenStream;

import java.util.Locale;

public final class LowerCaseFilter extends TokenFilter {

    public enum Variant { DEFAULT, GREEK, IRISH, TURKISH }

    private final Variant variant;

    public LowerCaseFilter(TokenStream input) {
        this(input, Variant.DEFAULT);
    }

    public LowerCaseFilter(TokenStream input, Variant variant) {
        super(input);
        this.variant = variant;
    }

    @Override
    public boolean incrementToken() {
        if (!input.incrementToken()) {
            return false;
        }
        switch (variant) {
            case TURKISH -> lowerTurkish();
            case IRISH -> lowerIrish();
            case GREEK -> lowerGreek();
            default -> lowerDefault();
        }
        return true;
    }

    private void lowerDefault() {
        char[] buf = token.buffer();
        int len = token.length();
        for (int i = 0; i < len; ) {
            int cp = Character.codePointAt(buf, i, len);
            int lower = Character.toLowerCase(cp);
            i += writeCodePoint(buf, i, cp, lower);
        }
    }

    private void lowerTurkish() {
        char[] buf = token.buffer();
        int len = token.length();
        for (int i = 0; i < len; i++) {
            char c = buf[i];
            if (c == 'I') {
                buf[i] = 'ı';
            } else if (c == 'İ') {
                buf[i] = 'i';
            } else {
                buf[i] = Character.toLowerCase(c);
            }
        }
    }

    private void lowerIrish() {
        char[] buf = token.buffer();
        int len = token.length();
        int start = 0;
        if (len > 1 && (buf[0] == 'h' || buf[0] == 'H' || buf[0] == 'n' || buf[0] == 'N' || buf[0] == 't' || buf[0] == 'T')
            && buf[1] == '\'') {
            start = 2;
        }
        for (int i = 0; i < len; i++) {
            if (i < start) {
                continue;
            }
            buf[i] = Character.toLowerCase(buf[i]);
        }
        if (start > 0) {
            buf[0] = Character.toLowerCase(buf[0]);
        }
    }

    private void lowerGreek() {
        char[] buf = token.buffer();
        int len = token.length();
        for (int i = 0; i < len; i++) {
            char c = buf[i];
            switch (c) {
                case 'Ά' -> c = 'α';
                case 'Έ' -> c = 'ε';
                case 'Ή' -> c = 'η';
                case 'Ί' -> c = 'ι';
                case 'Ό' -> c = 'ο';
                case 'Ύ' -> c = 'υ';
                case 'Ώ' -> c = 'ω';
                case 'Ϊ' -> c = 'ι';
                case 'Ϋ' -> c = 'υ';
                default -> c = Character.toLowerCase(c);
            }
            if ((c == 'ς') ) {
                c = 'σ';
            }
            buf[i] = c;
        }
        for (int i = 0; i < len; i++) {
            if (buf[i] == 'ς' && i == len - 1) {
                buf[i] = 'σ';
            }
        }
    }

    private static int writeCodePoint(char[] buf, int i, int origCp, int lower) {
        int origCount = Character.charCount(origCp);
        if (lower == origCp) {
            return origCount;
        }
        if (Character.charCount(lower) == origCount) {
            if (origCount == 1) {
                buf[i] = (char) lower;
            } else {
                buf[i] = Character.highSurrogate(lower);
                buf[i + 1] = Character.lowSurrogate(lower);
            }
        }
        return origCount;
    }
}
