package com.naqqa.elasticsearch.search.suggest.completion;

public final class FuzzyOptions {

    private int maxEdits = 1;
    private int fuzzyPrefixLength = 1;
    private int fuzzyMinLength = 3;
    private boolean unicodeAware = false;

    public int maxEdits() {
        return maxEdits;
    }

    public FuzzyOptions maxEdits(int maxEdits) {
        this.maxEdits = maxEdits;
        return this;
    }

    public int fuzzyPrefixLength() {
        return fuzzyPrefixLength;
    }

    public FuzzyOptions fuzzyPrefixLength(int fuzzyPrefixLength) {
        this.fuzzyPrefixLength = fuzzyPrefixLength;
        return this;
    }

    public int fuzzyMinLength() {
        return fuzzyMinLength;
    }

    public FuzzyOptions fuzzyMinLength(int fuzzyMinLength) {
        this.fuzzyMinLength = fuzzyMinLength;
        return this;
    }

    public boolean unicodeAware() {
        return unicodeAware;
    }

    public FuzzyOptions unicodeAware(boolean unicodeAware) {
        this.unicodeAware = unicodeAware;
        return this;
    }
}
