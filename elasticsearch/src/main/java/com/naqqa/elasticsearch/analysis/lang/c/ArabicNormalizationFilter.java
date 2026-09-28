package com.naqqa.elasticsearch.analysis.lang.c;

import com.naqqa.elasticsearch.analysis.TokenFilter;
import com.naqqa.elasticsearch.analysis.TokenStream;

public final class ArabicNormalizationFilter extends TokenFilter {

    private final ArabicNormalizer normalizer = new ArabicNormalizer();
    private final StringBuilder scratch = new StringBuilder();

    public ArabicNormalizationFilter(TokenStream input) {
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
