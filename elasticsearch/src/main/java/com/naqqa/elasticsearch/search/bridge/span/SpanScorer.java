package com.naqqa.elasticsearch.search.bridge.span;

import com.naqqa.elasticsearch.search.query.Scorer;
import com.naqqa.elasticsearch.search.query.TwoPhaseIterator;
import com.naqqa.elasticsearch.search.query.Weight;

import java.io.IOException;

final class SpanScorer extends Scorer {

    private final Spans spans;
    private final float boost;
    private int freq = -1;

    SpanScorer(Weight weight, Spans spans, float boost) {
        super(weight);
        this.spans = spans;
        this.boost = boost;
    }

    @Override
    public int docID() {
        return spans.docID();
    }

    @Override
    public int nextDoc() throws IOException {
        return doNext(spans.nextDoc());
    }

    @Override
    public int advance(int target) throws IOException {
        return doNext(spans.advance(target));
    }

    private int doNext(int doc) throws IOException {
        while (doc != NO_MORE_DOCS) {
            if (countPositions() > 0) {
                return doc;
            }
            doc = spans.nextDoc();
        }
        return NO_MORE_DOCS;
    }

    private int countPositions() throws IOException {
        int count = 0;
        while (spans.nextStartPosition() != Spans.NO_MORE_POSITIONS) {
            count++;
        }
        freq = count;
        return count;
    }

    @Override
    public long cost() {
        return spans.cost();
    }

    @Override
    public float score() {
        return boost * Math.max(1, freq);
    }

    @Override
    public TwoPhaseIterator twoPhaseIterator() {
        return null;
    }
}
