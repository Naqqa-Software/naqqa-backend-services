package com.naqqa.elasticsearch.search.query;

import com.naqqa.elasticsearch.codec.DocIdSetIterator;

import java.io.IOException;

public abstract class Scorer extends DocIdSetIterator {

    private final Weight weight;

    protected Scorer(Weight weight) {
        this.weight = weight;
    }

    public Weight weight() {
        return weight;
    }

    public abstract float score() throws IOException;

    public TwoPhaseIterator twoPhaseIterator() {
        return null;
    }

    public float getMaxScore(int upTo) throws IOException {
        return Float.POSITIVE_INFINITY;
    }

    public void setMinCompetitiveScore(float minScore) throws IOException {
    }

    public int advanceShallow(int target) throws IOException {
        return NO_MORE_DOCS;
    }
}
