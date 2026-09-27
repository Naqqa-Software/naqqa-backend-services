package com.naqqa.elasticsearch.search.suggest.term;

public final class TermSuggestOptions {

    private int size = 5;
    private SuggestMode suggestMode = SuggestMode.MISSING;
    private int maxEdits = 2;
    private int prefixLength = 1;
    private int minWordLength = 4;
    private DistanceMetric distanceMetric = DistanceMetric.INTERNAL;

    public int size() {
        return size;
    }

    public TermSuggestOptions size(int size) {
        this.size = size;
        return this;
    }

    public SuggestMode suggestMode() {
        return suggestMode;
    }

    public TermSuggestOptions suggestMode(SuggestMode mode) {
        this.suggestMode = mode;
        return this;
    }

    public int maxEdits() {
        return maxEdits;
    }

    public TermSuggestOptions maxEdits(int maxEdits) {
        if (maxEdits < 1 || maxEdits > 2) {
            throw new IllegalArgumentException("maxEdits must be 1 or 2");
        }
        this.maxEdits = maxEdits;
        return this;
    }

    public int prefixLength() {
        return prefixLength;
    }

    public TermSuggestOptions prefixLength(int prefixLength) {
        this.prefixLength = prefixLength;
        return this;
    }

    public int minWordLength() {
        return minWordLength;
    }

    public TermSuggestOptions minWordLength(int minWordLength) {
        this.minWordLength = minWordLength;
        return this;
    }

    public DistanceMetric distanceMetric() {
        return distanceMetric;
    }

    public TermSuggestOptions distanceMetric(DistanceMetric metric) {
        this.distanceMetric = metric;
        return this;
    }
}
