package com.naqqa.elasticsearch.analysis.synonym;

import com.naqqa.elasticsearch.analysis.TokenStream;

public final class SynonymFilter extends SynonymGraphFilter {

    public SynonymFilter(TokenStream input, SynonymMap map) {
        super(input, map, false);
    }
}
