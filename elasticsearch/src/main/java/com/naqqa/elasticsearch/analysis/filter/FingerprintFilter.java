package com.naqqa.elasticsearch.analysis.filter;

import com.naqqa.elasticsearch.analysis.TokenFilter;
import com.naqqa.elasticsearch.analysis.TokenStream;

import java.util.TreeSet;

public final class FingerprintFilter extends TokenFilter {

    private final char separator;
    private final int maxOutputSize;
    private boolean done;
    private boolean emitted;

    public FingerprintFilter(TokenStream input, char separator, int maxOutputSize) {
        super(input);
        this.separator = separator;
        this.maxOutputSize = maxOutputSize;
    }

    @Override
    public void reset() {
        super.reset();
        done = false;
        emitted = false;
    }

    @Override
    public boolean incrementToken() {
        if (emitted) {
            return false;
        }
        TreeSet<String> terms = new TreeSet<>();
        while (input.incrementToken()) {
            terms.add(token.term());
        }
        emitted = true;
        if (terms.isEmpty()) {
            return false;
        }
        StringBuilder sb = new StringBuilder();
        for (String t : terms) {
            if (sb.length() > 0) {
                sb.append(separator);
            }
            sb.append(t);
        }
        if (sb.length() > maxOutputSize) {
            return false;
        }
        token.clear();
        token.setTerm(sb);
        token.setOffset(0, sb.length());
        token.setPositionIncrement(1);
        return true;
    }
}
