package com.naqqa.elasticsearch.analysis.filter.worddelimiter;

import com.naqqa.elasticsearch.analysis.Tokenizer;

final class TestWhitespaceTokenizer extends Tokenizer {

    private int pos;

    TestWhitespaceTokenizer(String text) {
        setInput(text);
    }

    @Override
    public boolean incrementToken() {
        token.clear();
        int len = input.length();
        while (pos < len && Character.isWhitespace(input.charAt(pos))) {
            pos++;
        }
        if (pos >= len) {
            return false;
        }
        int start = pos;
        while (pos < len && !Character.isWhitespace(input.charAt(pos))) {
            pos++;
        }
        token.setTerm(input, start, pos);
        token.setOffset(correctOffset(start), correctOffset(pos));
        return true;
    }

    @Override
    public void reset() {
        super.reset();
        pos = 0;
    }
}
