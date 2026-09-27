package com.naqqa.elasticsearch.analysis.filter;

import com.naqqa.elasticsearch.analysis.TokenFilter;
import com.naqqa.elasticsearch.analysis.TokenStream;

import java.util.Set;
import java.util.regex.Pattern;

public final class KeywordMarkerFilter extends TokenFilter {

    private final Set<String> keywords;
    private final Pattern pattern;

    public KeywordMarkerFilter(TokenStream input, Set<String> keywords) {
        this(input, keywords, null);
    }

    public KeywordMarkerFilter(TokenStream input, Set<String> keywords, Pattern pattern) {
        super(input);
        this.keywords = keywords;
        this.pattern = pattern;
    }

    @Override
    public boolean incrementToken() {
        if (!input.incrementToken()) {
            return false;
        }
        if (!token.isKeyword()) {
            if (keywords != null && keywords.contains(token.term())) {
                token.setKeyword(true);
            } else if (pattern != null && pattern.matcher(token).matches()) {
                token.setKeyword(true);
            }
        }
        return true;
    }
}
