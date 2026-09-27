package com.naqqa.elasticsearch.analysis.filter;

import com.naqqa.elasticsearch.analysis.Token;
import com.naqqa.elasticsearch.analysis.TokenFilter;
import com.naqqa.elasticsearch.analysis.TokenStream;

import java.util.ArrayDeque;
import java.util.Deque;

public final class EdgeNGramTokenFilter extends TokenFilter {

    private final int minGram;
    private final int maxGram;
    private final boolean preserveOriginal;
    private final Deque<Token> pending = new ArrayDeque<>();

    public EdgeNGramTokenFilter(TokenStream input, int minGram, int maxGram, boolean preserveOriginal) {
        super(input);
        this.minGram = minGram;
        this.maxGram = maxGram;
        this.preserveOriginal = preserveOriginal;
    }

    @Override
    public void reset() {
        super.reset();
        pending.clear();
    }

    @Override
    public boolean incrementToken() {
        if (!pending.isEmpty()) {
            token.clear();
            token.copyFrom(pending.poll());
            token.setPositionIncrement(0);
            return true;
        }
        if (!input.incrementToken()) {
            return false;
        }
        String term = token.term();
        int[] cps = term.codePoints().toArray();
        int len = cps.length;
        Token base = token.copy();
        if (len < minGram) {
            return true;
        }
        int max = Math.min(maxGram, len);
        for (int size = minGram; size <= max; size++) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < size; i++) {
                sb.appendCodePoint(cps[i]);
            }
            Token t = base.copy();
            t.setTerm(sb);
            pending.add(t);
        }
        if (preserveOriginal && len > max) {
            pending.add(base.copy());
        }
        token.clear();
        token.copyFrom(pending.poll());
        return true;
    }
}
