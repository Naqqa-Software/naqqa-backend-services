package com.naqqa.elasticsearch.analysis.filter;

import com.naqqa.elasticsearch.analysis.TokenFilter;
import com.naqqa.elasticsearch.analysis.TokenStream;
import com.naqqa.elasticsearch.analysis.stem.Stemmer;

public final class StemmerFilter extends TokenFilter {

    private final Stemmer stemmer;
    private final StringBuilder scratch = new StringBuilder();

    public StemmerFilter(TokenStream input, Stemmer stemmer) {
        super(input);
        this.stemmer = stemmer;
    }

    @Override
    public boolean incrementToken() {
        if (!input.incrementToken()) {
            return false;
        }
        if (!token.isKeyword()) {
            scratch.setLength(0);
            scratch.append(token.buffer(), 0, token.length());
            stemmer.stem(scratch);
            token.setTerm(scratch);
        }
        return true;
    }
}
