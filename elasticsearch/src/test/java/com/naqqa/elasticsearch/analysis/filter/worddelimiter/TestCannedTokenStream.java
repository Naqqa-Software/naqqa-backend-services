package com.naqqa.elasticsearch.analysis.filter.worddelimiter;

import com.naqqa.elasticsearch.analysis.Token;
import com.naqqa.elasticsearch.analysis.TokenStream;

final class TestCannedTokenStream extends TokenStream {

    private final Token[] tokens;
    private int upto;

    TestCannedTokenStream(Token... tokens) {
        this.tokens = tokens;
    }

    static Token tok(String term, int start, int end) {
        return new Token(term, start, end);
    }

    @Override
    public boolean incrementToken() {
        if (upto >= tokens.length) {
            return false;
        }
        token.clear();
        token.copyFrom(tokens[upto++]);
        return true;
    }

    @Override
    public void reset() {
        upto = 0;
    }
}
