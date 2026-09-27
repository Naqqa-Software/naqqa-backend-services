package com.naqqa.elasticsearch.analysis.filter;

import com.naqqa.elasticsearch.analysis.FilteringTokenFilter;
import com.naqqa.elasticsearch.analysis.TokenStream;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class KeepWordFilter extends FilteringTokenFilter {

    private final Set<String> words;
    private final boolean ignoreCase;

    public KeepWordFilter(TokenStream input, List<String> words, boolean ignoreCase) {
        super(input);
        this.ignoreCase = ignoreCase;
        this.words = new HashSet<>();
        for (String w : words) {
            this.words.add(ignoreCase ? w.toLowerCase(Locale.ROOT) : w);
        }
    }

    @Override
    protected boolean accept() {
        String term = token.term();
        return words.contains(ignoreCase ? term.toLowerCase(Locale.ROOT) : term);
    }
}
