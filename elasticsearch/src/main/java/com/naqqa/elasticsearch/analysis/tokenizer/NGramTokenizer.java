package com.naqqa.elasticsearch.analysis.tokenizer;

import com.naqqa.elasticsearch.analysis.Tokenizer;

public class NGramTokenizer extends Tokenizer {

    protected final int minGram;
    protected final int maxGram;
    private final CodepointMatcher matcher;
    private int[] codePoints = new int[0];
    private int[] offsets = new int[0];
    private int numCodePoints;
    private int pos;
    private int gramSize;
    private int startPos;

    public interface CodepointMatcher {
        boolean isTokenChar(int codePoint);
    }

    public static final CodepointMatcher MATCH_ALL = cp -> true;

    public NGramTokenizer(int minGram, int maxGram) {
        this(minGram, maxGram, MATCH_ALL);
    }

    public NGramTokenizer(int minGram, int maxGram, CodepointMatcher matcher) {
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
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            codePoints[n] = cp;
            offsets[n] = i;
            n++;
            i += Character.charCount(cp);
        }
        offsets[n] = i;
        numCodePoints = n;
        pos = 0;
        gramSize = minGram;
        startPos = 0;
        advanceToRun();
    }

    private int runEnd;

    private void advanceToRun() {
        while (pos < numCodePoints && !matcher.isTokenChar(codePoints[pos])) {
            pos++;
        }
        startPos = pos;
        runEnd = pos;
        while (runEnd < numCodePoints && matcher.isTokenChar(codePoints[runEnd])) {
            runEnd++;
        }
        gramSize = minGram;
    }

    @Override
    public boolean incrementToken() {
        while (true) {
            if (startPos >= runEnd) {
                if (runEnd >= numCodePoints) {
                    return false;
                }
                pos = runEnd;
                advanceToRun();
                continue;
            }
            if (startPos + gramSize > runEnd) {
                startPos++;
                gramSize = minGram;
                continue;
            }
            token.clear();
            token.setTerm(codePointsToString(startPos, gramSize));
            token.setOffset(correctOffset(offsets[startPos]), correctOffset(offsets[startPos + gramSize]));
            gramSize++;
            if (gramSize > maxGram) {
                startPos++;
                gramSize = minGram;
            }
            return true;
        }
    }

    private String codePointsToString(int from, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.appendCodePoint(codePoints[from + i]);
        }
        return sb.toString();
    }
}
