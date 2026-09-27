package com.naqqa.elasticsearch.analysis;

public abstract class TokenStream implements AutoCloseable {

    protected final Token token;

    protected TokenStream() {
        this.token = new Token();
    }

    protected TokenStream(TokenStream input) {
        this.token = input.token;
    }

    public final Token token() {
        return token;
    }

    public abstract boolean incrementToken();

    public void reset() {
    }

    public void end() {
        token.setPositionIncrement(0);
    }

    @Override
    public void close() {
    }
}
