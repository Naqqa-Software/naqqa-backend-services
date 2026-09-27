package com.naqqa.elasticsearch.analysis.filter;

import com.naqqa.elasticsearch.analysis.TokenFilter;
import com.naqqa.elasticsearch.analysis.TokenStream;

public final class UpperCaseFilter extends TokenFilter {

    public UpperCaseFilter(TokenStream input) {
        super(input);
    }

    @Override
    public boolean incrementToken() {
        if (!input.incrementToken()) {
            return false;
        }
        String upper = token.term().toUpperCase(java.util.Locale.ROOT);
        token.setTerm(upper);
        return true;
    }
}
