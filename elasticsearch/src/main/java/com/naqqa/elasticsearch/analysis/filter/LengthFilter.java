package com.naqqa.elasticsearch.analysis.filter;

import com.naqqa.elasticsearch.analysis.FilteringTokenFilter;
import com.naqqa.elasticsearch.analysis.TokenStream;

public final class LengthFilter extends FilteringTokenFilter {

    private final int min;
    private final int max;

    public LengthFilter(TokenStream input, int min, int max) {
        super(input);
        this.min = min;
        this.max = max;
    }

    @Override
    protected boolean accept() {
        int len = token.length();
        return len >= min && len <= max;
    }
}
