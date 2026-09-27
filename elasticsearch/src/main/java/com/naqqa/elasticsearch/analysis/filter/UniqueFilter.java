package com.naqqa.elasticsearch.analysis.filter;

import com.naqqa.elasticsearch.analysis.FilteringTokenFilter;
import com.naqqa.elasticsearch.analysis.TokenStream;

import java.util.HashSet;
import java.util.Set;

public final class UniqueFilter extends FilteringTokenFilter {

    private final boolean onlyOnSamePosition;
    private final Set<String> seen = new HashSet<>();
    private int lastPos = -1;
    private int pos = -1;

    public UniqueFilter(TokenStream input, boolean onlyOnSamePosition) {
        super(input);
        this.onlyOnSamePosition = onlyOnSamePosition;
    }

    @Override
    protected boolean accept() {
        if (onlyOnSamePosition) {
            pos += token.positionIncrement();
            if (pos != lastPos) {
                seen.clear();
                lastPos = pos;
            }
        }
        return seen.add(token.term());
    }

    @Override
    public void reset() {
        super.reset();
        seen.clear();
        lastPos = -1;
        pos = -1;
    }
}
