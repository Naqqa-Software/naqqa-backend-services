package com.naqqa.elasticsearch.analysis.lang.a;

import com.naqqa.elasticsearch.analysis.TokenFilter;
import com.naqqa.elasticsearch.analysis.TokenStream;

public final class IrishLowerCaseFilter extends TokenFilter {

    public IrishLowerCaseFilter(TokenStream input) {
        super(input);
    }

    @Override
    public boolean incrementToken() {
        if (!input.incrementToken()) {
            return false;
        }
        char[] buf = token.buffer();
        int len = token.length();
        int idx = 0;
        if (len > 1 && (buf[0] == 'n' || buf[0] == 't') && isUpperVowel(buf[1])) {
            buf = token.resizeBuffer(len + 1);
            for (int i = len; i > 1; i--) {
                buf[i] = buf[i - 1];
            }
            buf[1] = '-';
            token.setLength(len + 1);
            idx = 2;
            len = len + 1;
        }
        for (int i = idx; i < len; ) {
            int cp = Character.codePointAt(buf, i, len);
            int lower = Character.toLowerCase(cp);
            i += writeCodePoint(buf, i, cp, lower);
        }
        return true;
    }

    private static boolean isUpperVowel(int v) {
        switch (v) {
            case 'A':
            case 'E':
            case 'I':
            case 'O':
            case 'U':
            case 'Á':
            case 'É':
            case 'Í':
            case 'Ó':
            case 'Ú':
                return true;
            default:
                return false;
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
