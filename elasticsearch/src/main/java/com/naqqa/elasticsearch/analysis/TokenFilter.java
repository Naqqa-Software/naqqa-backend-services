package com.naqqa.elasticsearch.analysis;

public abstract class TokenFilter extends TokenStream {

    protected final TokenStream input;

    protected TokenFilter(TokenStream input) {
        super(input);
        this.input = input;
    }

    @Override
    public void reset() {
        input.reset();
    }

    @Override
    public void end() {
        input.end();
    }

    @Override
    public void close() {
        input.close();
    }
}
