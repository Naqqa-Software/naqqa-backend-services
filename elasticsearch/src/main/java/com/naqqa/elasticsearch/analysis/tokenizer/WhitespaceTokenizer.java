package com.naqqa.elasticsearch.analysis.tokenizer;

import com.naqqa.elasticsearch.analysis.Tokenizer;

public class WhitespaceTokenizer extends Tokenizer {

    private final int maxTokenLength;
    private int pos;

    public WhitespaceTokenizer() {
        this(255);
    }

    public WhitespaceTokenizer(int maxTokenLength) {
        this.maxTokenLength = maxTokenLength;
    }

    @Override
    public void reset() {
        super.reset();
        pos = 0;
    }

    protected boolean isTokenChar(int cp) {
        return !Character.isWhitespace(cp);
    }

    @Override
    public boolean incrementToken() {
        CharSequence text = input;
        int len = text.length();
        while (pos < len) {
            int cp = Character.codePointAt(text, pos);
            if (isTokenChar(cp)) {
                break;
            }
            pos += Character.charCount(cp);
        }
        if (pos >= len) {
            return false;
        }
        int start = pos;
        StringBuilder sb = new StringBuilder();
        while (pos < len && sb.length() < maxTokenLength) {
            int cp = Character.codePointAt(text, pos);
            if (!isTokenChar(cp)) {
                break;
            }
            sb.appendCodePoint(cp);
            pos += Character.charCount(cp);
        }
        while (pos < len) {
            int cp = Character.codePointAt(text, pos);
            if (!isTokenChar(cp)) {
                break;
            }
            pos += Character.charCount(cp);
        }
        token.clear();
        token.setTerm(sb);
        token.setOffset(correctOffset(start), correctOffset(pos));
        return true;
    }
}
