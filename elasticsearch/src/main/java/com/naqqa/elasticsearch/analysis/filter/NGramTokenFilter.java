package com.naqqa.elasticsearch.analysis.filter;

import com.naqqa.elasticsearch.analysis.Token;
import com.naqqa.elasticsearch.analysis.TokenFilter;
import com.naqqa.elasticsearch.analysis.TokenStream;

import java.util.ArrayDeque;
import java.util.Deque;

public final class NGramTokenFilter extends TokenFilter {

    private final int minGram;
    private final int maxGram;
    private final Deque<Token> pending = new ArrayDeque<>();

    public NGramTokenFilter(TokenStream input, int minGram, int maxGram) {
        super(input);
        this.minGram = minGram;
        this.maxGram = maxGram;
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
        int len = term.codePointCount(0, term.length());
        int[] cps = term.codePoints().toArray();
        int start = token.startOffset();
        int end = token.endOffset();
        Token base = token.copy();
        boolean first = true;
        for (int size = minGram; size <= maxGram && size <= len; size++) {
            for (int from = 0; from + size <= len; from++) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < size; i++) {
                    sb.appendCodePoint(cps[from + i]);
                }
                Token t = base.copy();
                t.setTerm(sb);
                pending.add(t);
            }
        }
        if (pending.isEmpty()) {
            return incrementToken();
        }
        token.clear();
        token.copyFrom(pending.poll());
        return true;
    }
}
