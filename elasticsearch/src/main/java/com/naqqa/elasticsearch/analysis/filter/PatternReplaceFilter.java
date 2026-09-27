package com.naqqa.elasticsearch.analysis.filter;

import com.naqqa.elasticsearch.analysis.TokenFilter;
import com.naqqa.elasticsearch.analysis.TokenStream;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PatternReplaceFilter extends TokenFilter {

    private final Pattern pattern;
    private final String replacement;
    private final boolean all;

    public PatternReplaceFilter(TokenStream input, Pattern pattern, String replacement, boolean all) {
        super(input);
        this.pattern = pattern;
        this.replacement = replacement == null ? "" : replacement;
        this.all = all;
    }

    @Override
    public boolean incrementToken() {
        if (!input.incrementToken()) {
            return false;
        }
        Matcher m = pattern.matcher(token.term());
        String result = all ? m.replaceAll(replacement) : m.replaceFirst(replacement);
        token.setTerm(result);
        return true;
    }
}
