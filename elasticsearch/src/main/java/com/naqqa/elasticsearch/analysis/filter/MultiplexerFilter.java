package com.naqqa.elasticsearch.analysis.filter;

import com.naqqa.elasticsearch.analysis.Token;
import com.naqqa.elasticsearch.analysis.TokenFilter;
import com.naqqa.elasticsearch.analysis.TokenStream;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Function;

public final class MultiplexerFilter extends TokenFilter {

    private final List<Function<TokenStream, TokenStream>> branches;
    private final boolean preserveOriginal;
    private final Deque<Token> pending = new ArrayDeque<>();
    private boolean firstOverall = true;

    public MultiplexerFilter(TokenStream input, List<Function<TokenStream, TokenStream>> branches, boolean preserveOriginal) {
        super(input);
        this.branches = branches;
        this.preserveOriginal = preserveOriginal;
    }

    @Override
    public void reset() {
        super.reset();
        pending.clear();
        firstOverall = true;
    }

    @Override
    public boolean incrementToken() {
        if (!pending.isEmpty()) {
            token.clear();
            token.copyFrom(pending.poll());
            return true;
        }
        if (!input.incrementToken()) {
            return false;
        }
        Token original = token.copy();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<Token> variants = new java.util.ArrayList<>();
        if (preserveOriginal) {
            variants.add(original.copy());
            seen.add(original.term());
        }
        for (Function<TokenStream, TokenStream> branch : branches) {
            SingleTokenStream source = new SingleTokenStream(original);
            TokenStream chain = branch.apply(source);
            chain.reset();
            while (chain.incrementToken()) {
                Token t = chain.token().copy();
                if (seen.add(t.term())) {
                    variants.add(t);
                }
            }
            chain.end();
            chain.close();
        }
        if (variants.isEmpty()) {
            variants.add(original);
        }
        for (int i = 0; i < variants.size(); i++) {
            Token t = variants.get(i);
            t.setOffset(original.startOffset(), original.endOffset());
            t.setPositionIncrement(i == 0 ? original.positionIncrement() : 0);
            pending.add(t);
        }
        token.clear();
        token.copyFrom(pending.poll());
        return true;
    }

    private static final class SingleTokenStream extends TokenStream {
        private final Token source;
        private boolean done;

        SingleTokenStream(Token source) {
            this.source = source;
        }

        @Override
        public void reset() {
            done = false;
        }

        @Override
        public boolean incrementToken() {
            if (done) {
                return false;
            }
            done = true;
            token.clear();
            token.copyFrom(source);
            return true;
        }
    }
}
