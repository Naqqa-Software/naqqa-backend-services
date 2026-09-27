package com.naqqa.elasticsearch.analysis.filter;

import com.naqqa.elasticsearch.analysis.Token;
import com.naqqa.elasticsearch.analysis.TokenFilter;
import com.naqqa.elasticsearch.analysis.TokenStream;
import com.naqqa.elasticsearch.analysis.phonetic.PhoneticEncoder;

import java.util.ArrayDeque;
import java.util.Deque;

public final class PhoneticFilter extends TokenFilter {

    private final PhoneticEncoder encoder;
    private final boolean replace;
    private final Deque<Token> pending = new ArrayDeque<>();

    public PhoneticFilter(TokenStream input, PhoneticEncoder encoder, boolean replace) {
        super(input);
        this.encoder = encoder;
        this.replace = replace;
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
        String code = encoder.encode(token.term());
        if (code == null || code.isEmpty()) {
            return true;
        }
        if (replace) {
            token.setTerm(code);
        } else {
            Token t = token.copy();
            t.setTerm(code);
            pending.add(t);
        }
        return true;
    }
}
