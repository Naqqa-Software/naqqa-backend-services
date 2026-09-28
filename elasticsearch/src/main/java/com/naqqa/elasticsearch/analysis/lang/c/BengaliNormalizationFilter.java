package com.naqqa.elasticsearch.analysis.lang.c;

import com.naqqa.elasticsearch.analysis.TokenFilter;
import com.naqqa.elasticsearch.analysis.TokenStream;

public final class BengaliNormalizationFilter extends TokenFilter {

    private final BengaliNormalizer normalizer = new BengaliNormalizer();
    private final StringBuilder scratch = new StringBuilder();

    public BengaliNormalizationFilter(TokenStream input) {
        super(input);
    }

    @Override
    public boolean incrementToken() {
        if (!input.incrementToken()) {
            return false;
        }
        if (!token.isKeyword()) {
            scratch.setLength(0);
            scratch.append(token.buffer(), 0, token.length());
            normalizer.stem(scratch);
            token.setTerm(scratch);
        }
        return true;
    }
}
