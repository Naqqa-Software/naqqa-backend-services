package com.naqqa.elasticsearch.analysis.filter;

import com.naqqa.elasticsearch.analysis.TokenFilter;
import com.naqqa.elasticsearch.analysis.TokenStream;

public final class ClassicFilter extends TokenFilter {

    public ClassicFilter(TokenStream input) {
        super(input);
    }

    @Override
    public boolean incrementToken() {
        if (!input.incrementToken()) {
            return false;
        }
        char[] buf = token.buffer();
        int len = token.length();
        if ("<ACRONYM>".equals(token.type())) {
            int out = 0;
            for (int i = 0; i < len; i++) {
                if (buf[i] != '.') {
                    buf[out++] = buf[i];
                }
            }
            token.setLength(out);
        } else if ("<APOSTROPHE>".equals(token.type())) {
            for (int i = 0; i < len; i++) {
                if (buf[i] == '\'' && i + 1 < len && (buf[i + 1] == 's' || buf[i + 1] == 'S') && i + 2 == len) {
                    token.setLength(i);
                    break;
                }
            }
        }
        return true;
    }
}
