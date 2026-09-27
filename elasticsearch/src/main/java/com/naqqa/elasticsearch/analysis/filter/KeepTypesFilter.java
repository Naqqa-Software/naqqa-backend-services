package com.naqqa.elasticsearch.analysis.filter;

import com.naqqa.elasticsearch.analysis.FilteringTokenFilter;
import com.naqqa.elasticsearch.analysis.TokenStream;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class KeepTypesFilter extends FilteringTokenFilter {

    private final Set<String> types;
    private final boolean exclude;

    public KeepTypesFilter(TokenStream input, List<String> types, boolean exclude) {
        super(input);
        this.types = new HashSet<>(types);
        this.exclude = exclude;
    }

    @Override
    protected boolean accept() {
        boolean contained = types.contains(token.type());
        return exclude ? !contained : contained;
    }
}
