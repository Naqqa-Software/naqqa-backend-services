package com.naqqa.elasticsearch.analysis.filter;

import com.naqqa.elasticsearch.analysis.TokenFilter;
import com.naqqa.elasticsearch.analysis.TokenStream;

public final class TruncateFilter extends TokenFilter {

    private final int length;

    public TruncateFilter(TokenStream input, int length) {
        super(input);
        if (length < 1) {
            throw new IllegalArgumentException("length parameter must be a positive number");
        }
        this.length = length;
    }

    @Override
    public boolean incrementToken() {
        if (!input.incrementToken()) {
            return false;
        }
        if (!token.isKeyword() && token.length() > length) {
            token.setLength(length);
        }
        return true;
    }
}
