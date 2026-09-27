package com.naqqa.elasticsearch.analysis.tokenizer;

import com.naqqa.elasticsearch.analysis.Tokenizer;

public final class KeywordTokenizer extends Tokenizer {

    private final int bufferSize;
    private boolean done;

    public KeywordTokenizer() {
        this(256);
    }

    public KeywordTokenizer(int bufferSize) {
        this.bufferSize = bufferSize;
    }

    @Override
    public void reset() {
        super.reset();
        done = false;
    }

    @Override
    public boolean incrementToken() {
        if (done) {
            return false;
        }
        done = true;
        token.clear();
        token.setTerm(input.toString());
        token.setOffset(correctOffset(0), correctOffset(input.length()));
        token.setKeyword(true);
        return true;
    }
}
