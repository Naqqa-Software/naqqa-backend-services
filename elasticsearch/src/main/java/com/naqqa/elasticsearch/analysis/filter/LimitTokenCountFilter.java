package com.naqqa.elasticsearch.analysis.filter;

import com.naqqa.elasticsearch.analysis.TokenFilter;
import com.naqqa.elasticsearch.analysis.TokenStream;

public final class LimitTokenCountFilter extends TokenFilter {

    private final int maxTokenCount;
    private final boolean consumeAllTokens;
    private int count;

    public LimitTokenCountFilter(TokenStream input, int maxTokenCount, boolean consumeAllTokens) {
        super(input);
        this.maxTokenCount = maxTokenCount;
        this.consumeAllTokens = consumeAllTokens;
    }

    @Override
    public void reset() {
        super.reset();
        count = 0;
    }

    @Override
    public boolean incrementToken() {
        if (count >= maxTokenCount) {
            if (consumeAllTokens) {
                while (input.incrementToken()) {
                    // drain
                }
            }
            return false;
        }
        if (!input.incrementToken()) {
            return false;
        }
        count++;
        return true;
    }
}
