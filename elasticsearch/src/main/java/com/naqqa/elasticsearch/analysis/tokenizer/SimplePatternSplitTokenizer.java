package com.naqqa.elasticsearch.analysis.tokenizer;

import com.naqqa.elasticsearch.analysis.Tokenizer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SimplePatternSplitTokenizer extends Tokenizer {

    private final Pattern pattern;
    private Matcher matcher;
    private int last;
    private int searchFrom;
    private boolean finished;

    public SimplePatternSplitTokenizer(Pattern pattern) {
        this.pattern = pattern;
    }

    @Override
    public void reset() {
        super.reset();
        matcher = pattern.matcher(input);
        last = 0;
        searchFrom = 0;
        finished = false;
    }

    @Override
    public boolean incrementToken() {
        if (finished) {
            return false;
        }
        int len = input.length();
        while (searchFrom <= len && matcher.find(searchFrom)) {
            int start = matcher.start();
            int end = matcher.end();
            if (end == start) {
                searchFrom = end + 1;
                continue;
            }
            searchFrom = end;
            int tokenStart = last;
            last = end;
            token.clear();
            token.setTerm(input, tokenStart, start);
            token.setOffset(correctOffset(tokenStart), correctOffset(start));
            return true;
        }
        finished = true;
        token.clear();
        token.setTerm(input, last, len);
        token.setOffset(correctOffset(last), correctOffset(len));
        return true;
    }
}
