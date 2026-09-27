package com.naqqa.elasticsearch.analysis.filter;

import com.naqqa.elasticsearch.analysis.TokenFilter;
import com.naqqa.elasticsearch.analysis.TokenStream;

public final class TrimFilter extends TokenFilter {

    public TrimFilter(TokenStream input) {
        super(input);
    }

    @Override
    public boolean incrementToken() {
        if (!input.incrementToken()) {
            return false;
        }
        char[] buf = token.buffer();
        int len = token.length();
        int start = 0;
        while (start < len && Character.isWhitespace(buf[start])) {
            start++;
        }
        int end = len;
        while (end > start && Character.isWhitespace(buf[end - 1])) {
            end--;
        }
        if (start > 0 || end < len) {
            token.setTerm(buf, start, end - start);
        }
        return true;
    }
}
