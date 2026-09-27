package com.naqqa.elasticsearch.search.bridge.geo;

import com.naqqa.elasticsearch.common.util.FixedBitSet;
import com.naqqa.elasticsearch.search.query.Scorer;
import com.naqqa.elasticsearch.search.query.Weight;

final class GeoBitSetScorer extends Scorer {

    private final FixedBitSet bits;
    private final float boost;
    private int doc = -1;

    GeoBitSetScorer(Weight weight, FixedBitSet bits, float boost) {
        super(weight);
        this.bits = bits;
        this.boost = boost;
    }

    @Override
    public int docID() {
        return doc;
    }

    @Override
    public int nextDoc() {
        return advance(doc + 1);
    }

    @Override
    public int advance(int target) {
        if (target >= bits.length()) {
            doc = NO_MORE_DOCS;
            return NO_MORE_DOCS;
        }
        int next = bits.nextSetBit(target);
        doc = next < 0 ? NO_MORE_DOCS : next;
        return doc;
    }

    @Override
    public long cost() {
        return bits.cardinality();
    }

    @Override
    public float score() {
        return boost;
    }

    @Override
    public float getMaxScore(int upTo) {
        return boost;
    }
}
