package com.naqqa.elasticsearch.analysis.filter;

import com.naqqa.elasticsearch.analysis.Token;
import com.naqqa.elasticsearch.analysis.TokenFilter;
import com.naqqa.elasticsearch.analysis.TokenStream;

import java.util.HashSet;
import java.util.Set;

public final class RemoveDuplicatesFilter extends TokenFilter {

    private final Set<String> seenAtPosition = new HashSet<>();
    private int pos = -1;

    public RemoveDuplicatesFilter(TokenStream input) {
        super(input);
    }

    @Override
    public void reset() {
        super.reset();
        seenAtPosition.clear();
        pos = -1;
    }

    @Override
    public boolean incrementToken() {
        while (input.incrementToken()) {
            int inc = token.positionIncrement();
            if (inc > 0) {
                seenAtPosition.clear();
                pos += inc;
            }
            if (seenAtPosition.add(token.term())) {
                if (inc == 0 && pos >= 0) {
                    token.setPositionIncrement(0);
                }
                return true;
            }
        }
        return false;
    }
}
