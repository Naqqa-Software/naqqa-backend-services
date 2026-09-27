package com.naqqa.elasticsearch.analysis.tokenizer;

import com.naqqa.elasticsearch.analysis.Tokenizer;

import java.util.Set;

public final class CharGroupTokenizer extends Tokenizer {

    private final Set<Integer> delimiters;
    private final int maxTokenLength;
    private int pos;

    public CharGroupTokenizer(Set<Integer> delimiters, int maxTokenLength) {
        this.delimiters = delimiters;
        this.maxTokenLength = maxTokenLength;
    }

    @Override
    public void reset() {
        super.reset();
        pos = 0;
    }

    @Override
    public boolean incrementToken() {
        CharSequence text = input;
        int len = text.length();
        while (pos < len && delimiters.contains(Character.codePointAt(text, pos))) {
            pos += Character.charCount(Character.codePointAt(text, pos));
        }
        if (pos >= len) {
            return false;
        }
        int start = pos;
        StringBuilder sb = new StringBuilder();
        while (pos < len) {
            int cp = Character.codePointAt(text, pos);
            if (delimiters.contains(cp) || sb.length() >= maxTokenLength) {
                break;
            }
            sb.appendCodePoint(cp);
            pos += Character.charCount(cp);
        }
        token.clear();
        token.setTerm(sb);
        token.setOffset(correctOffset(start), correctOffset(pos));
        return true;
    }
}
