package com.naqqa.elasticsearch.analysis.tokenizer;

import com.naqqa.elasticsearch.analysis.Tokenizer;

public final class EdgeNGramTokenizer extends Tokenizer {

    private final int minGram;
    private final int maxGram;
    private final NGramTokenizer.CodepointMatcher matcher;
    private int[] codePoints = new int[0];
    private int[] offsets = new int[0];
    private int numCodePoints;
    private int gramSize;
    private boolean emitted;

    public EdgeNGramTokenizer(int minGram, int maxGram) {
        this(minGram, maxGram, NGramTokenizer.MATCH_ALL);
    }

    public EdgeNGramTokenizer(int minGram, int maxGram, NGramTokenizer.CodepointMatcher matcher) {
        if (minGram < 1 || maxGram < minGram) {
            throw new IllegalArgumentException("invalid ngram sizes");
        }
        this.minGram = minGram;
        this.maxGram = maxGram;
        this.matcher = matcher;
    }

    @Override
    public void reset() {
        super.reset();
        String text = input.toString();
        int cap = text.length() + 1;
        if (codePoints.length < cap) {
            codePoints = new int[cap];
            offsets = new int[cap];
        }
        int n = 0;
        int i = 0;
        while (i < text.length() && matcher.isTokenChar(text.codePointAt(i))) {
            int cp = text.codePointAt(i);
            codePoints[n] = cp;
            offsets[n] = i;
            n++;
            i += Character.charCount(cp);
        }
        offsets[n] = i;
        numCodePoints = n;
        gramSize = minGram;
        emitted = false;
    }

    @Override
    public boolean incrementToken() {
        if (gramSize > maxGram || gramSize > numCodePoints) {
            return false;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < gramSize; i++) {
            sb.appendCodePoint(codePoints[i]);
        }
        token.clear();
        token.setTerm(sb);
        token.setOffset(correctOffset(offsets[0]), correctOffset(offsets[gramSize]));
        gramSize++;
        emitted = true;
        return true;
    }
}
