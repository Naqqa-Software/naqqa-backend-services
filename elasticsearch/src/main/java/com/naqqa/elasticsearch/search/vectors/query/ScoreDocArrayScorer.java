package com.naqqa.elasticsearch.search.vectors.query;

import com.naqqa.elasticsearch.codec.DocIdSetIterator;
import com.naqqa.elasticsearch.search.execution.ScoreDoc;
import com.naqqa.elasticsearch.search.query.Scorer;
import com.naqqa.elasticsearch.search.query.Weight;

import java.util.Arrays;

final class ScoreDocArrayScorer extends Scorer {

    private final ScoreDoc[] sorted;
    private final float boost;
    private int index = -1;

    ScoreDocArrayScorer(Weight weight, ScoreDoc[] scoreDocs, float boost) {
        super(weight);
        this.sorted = scoreDocs.clone();
        Arrays.sort(this.sorted, (a, b) -> Integer.compare(a.doc, b.doc));
        this.boost = boost;
    }

    @Override
    public int docID() {
        return index < 0 ? -1 : (index >= sorted.length ? DocIdSetIterator.NO_MORE_DOCS : sorted[index].doc);
    }

    @Override
    public int nextDoc() {
        index++;
        return docID();
    }

    @Override
    public int advance(int target) {
        while (index < sorted.length && docID() < target) {
            index++;
        }
        return docID();
    }

    @Override
    public long cost() {
        return sorted.length;
    }

    @Override
    public float score() {
        return boost * sorted[index].score;
    }

    @Override
    public float getMaxScore(int upTo) {
        float max = 0f;
        for (ScoreDoc sd : sorted) {
            if (sd.doc <= upTo) {
                max = Math.max(max, sd.score);
            }
        }
        return boost * max;
    }
}
