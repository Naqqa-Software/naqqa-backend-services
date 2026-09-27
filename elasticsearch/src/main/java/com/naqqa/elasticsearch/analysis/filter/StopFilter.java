package com.naqqa.elasticsearch.analysis.filter;

import com.naqqa.elasticsearch.analysis.FilteringTokenFilter;
import com.naqqa.elasticsearch.analysis.TokenStream;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class StopFilter extends FilteringTokenFilter {

    private final Set<String> stopWords;
    private final boolean ignoreCase;
    private final boolean removeTrailing;
    private boolean isFirst = true;

    public StopFilter(TokenStream input, List<String> stopWords, boolean ignoreCase) {
        this(input, stopWords, ignoreCase, true);
    }

    public StopFilter(TokenStream input, List<String> stopWords, boolean ignoreCase, boolean removeTrailing) {
        super(input);
        this.ignoreCase = ignoreCase;
        this.removeTrailing = removeTrailing;
        this.stopWords = new HashSet<>();
        for (String w : stopWords) {
            this.stopWords.add(ignoreCase ? w.toLowerCase(Locale.ROOT) : w);
        }
    }

    @Override
    protected boolean accept() {
        if (token.isKeyword()) {
            return true;
        }
        String term = token.term();
        String key = ignoreCase ? term.toLowerCase(Locale.ROOT) : term;
        return !stopWords.contains(key);
    }
}
