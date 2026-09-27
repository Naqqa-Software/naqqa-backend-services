package com.naqqa.elasticsearch.analysis.filter;

import com.naqqa.elasticsearch.analysis.TokenFilter;
import com.naqqa.elasticsearch.analysis.TokenStream;

public final class ApostropheFilter extends TokenFilter {

    public ApostropheFilter(TokenStream input) {
        super(input);
    }

    @Override
    public boolean incrementToken() {
        if (!input.incrementToken()) {
            return false;
        }
        char[] buf = token.buffer();
        int len = token.length();
        for (int i = 0; i < len; i++) {
            if (buf[i] == '\'' || buf[i] == '’' || buf[i] == '＇') {
                token.setLength(i);
                break;
            }
        }
        return true;
    }
}
