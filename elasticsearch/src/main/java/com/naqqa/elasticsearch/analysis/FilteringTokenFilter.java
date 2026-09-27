package com.naqqa.elasticsearch.analysis;

public abstract class FilteringTokenFilter extends TokenFilter {

    private int skippedPositions;

    protected FilteringTokenFilter(TokenStream input) {
        super(input);
    }

    protected abstract boolean accept();

    @Override
    public final boolean incrementToken() {
        skippedPositions = 0;
        while (input.incrementToken()) {
            if (accept()) {
                if (skippedPositions != 0) {
                    token.setPositionIncrement(token.positionIncrement() + skippedPositions);
                }
                return true;
            }
            skippedPositions += token.positionIncrement();
        }
        return false;
    }

    @Override
    public void reset() {
        super.reset();
        skippedPositions = 0;
    }

    @Override
    public void end() {
        super.end();
        token.setPositionIncrement(token.positionIncrement() + skippedPositions);
    }
}
