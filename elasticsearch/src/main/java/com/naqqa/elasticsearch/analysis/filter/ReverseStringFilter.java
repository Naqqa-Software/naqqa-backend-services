package com.naqqa.elasticsearch.analysis.filter;

import com.naqqa.elasticsearch.analysis.TokenFilter;
import com.naqqa.elasticsearch.analysis.TokenStream;

public final class ReverseStringFilter extends TokenFilter {

    public ReverseStringFilter(TokenStream input) {
        super(input);
    }

    @Override
    public boolean incrementToken() {
        if (!input.incrementToken()) {
            return false;
        }
        char[] buf = token.buffer();
        int len = token.length();
        int i = 0;
        int j = len - 1;
        while (i < j) {
            int cpFront = Character.codePointAt(buf, i, len);
            int frontCount = Character.charCount(cpFront);
            int cpBack = Character.codePointBefore(buf, j + 1);
            int backCount = Character.charCount(cpBack);
            if (frontCount == 1 && backCount == 1) {
                char tmp = buf[i];
                buf[i] = buf[j];
                buf[j] = tmp;
                i++;
                j--;
            } else {
                reverseRange(buf, 0, len);
                break;
            }
        }
        return true;
    }

    private static void reverseRange(char[] buf, int from, int to) {
        String s = new String(buf, from, to - from);
        StringBuilder sb = new StringBuilder(s.length());
        int i = s.length();
        while (i > 0) {
            int cp = s.codePointBefore(i);
            sb.appendCodePoint(cp);
            i -= Character.charCount(cp);
        }
        sb.getChars(0, sb.length(), buf, from);
    }
}
