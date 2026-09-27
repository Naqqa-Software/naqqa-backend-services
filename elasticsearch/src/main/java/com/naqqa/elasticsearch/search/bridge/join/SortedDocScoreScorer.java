package com.naqqa.elasticsearch.search.bridge.join;

import com.naqqa.elasticsearch.search.query.Scorer;
import com.naqqa.elasticsearch.search.query.Weight;

final class SortedDocScoreScorer extends Scorer {

    private final int[] docs;
    private final float[] scores;
    private int idx = -1;

    SortedDocScoreScorer(Weight weight, int[] docs, float[] scores) {
        super(weight);
        this.docs = docs;
        this.scores = scores;
    }

    @Override
    public int docID() {
        return idx < 0 ? -1 : (idx < docs.length ? docs[idx] : NO_MORE_DOCS);
    }

    @Override
    public int nextDoc() {
        idx++;
        return idx < docs.length ? docs[idx] : NO_MORE_DOCS;
    }

    @Override
    public int advance(int target) {
        idx = idx < 0 ? 0 : idx;
        while (idx < docs.length && docs[idx] < target) {
            idx++;
        }
        return idx < docs.length ? docs[idx] : NO_MORE_DOCS;
    }

    @Override
    public long cost() {
        return docs.length;
    }

    @Override
    public float score() {
        return scores[idx];
    }

    @Override
    public float getMaxScore(int upTo) {
        float max = 0f;
        for (int i = 0; i < docs.length; i++) {
            if (docs[i] <= upTo) {
                max = Math.max(max, scores[i]);
            }
        }
        return max;
    }
}
