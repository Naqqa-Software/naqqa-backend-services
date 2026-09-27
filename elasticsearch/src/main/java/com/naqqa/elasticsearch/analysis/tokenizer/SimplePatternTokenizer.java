package com.naqqa.elasticsearch.analysis.tokenizer;

import com.naqqa.elasticsearch.analysis.Tokenizer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SimplePatternTokenizer extends Tokenizer {

    private final Pattern pattern;
    private Matcher matcher;
    private int searchFrom;

    public SimplePatternTokenizer(Pattern pattern) {
        this.pattern = pattern;
    }

    @Override
    public void reset() {
        super.reset();
        matcher = pattern.matcher(input);
        searchFrom = 0;
    }

    @Override
    public boolean incrementToken() {
        int len = input.length();
        while (searchFrom <= len && matcher.find(searchFrom)) {
            int start = matcher.start();
            int end = matcher.end();
            searchFrom = end > start ? end : end + 1;
            if (end > start) {
                token.clear();
                token.setTerm(input, start, end);
                token.setOffset(correctOffset(start), correctOffset(end));
                return true;
            }
        }
        return false;
    }
}
