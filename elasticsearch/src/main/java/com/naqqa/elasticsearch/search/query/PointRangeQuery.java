package com.naqqa.elasticsearch.search.query;

import com.naqqa.elasticsearch.codec.NumericUtils;
import com.naqqa.elasticsearch.codec.points.BKDReader;
import com.naqqa.elasticsearch.codec.points.IntersectVisitor;
import com.naqqa.elasticsearch.codec.points.Relation;
import com.naqqa.elasticsearch.common.util.FixedBitSet;
import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.LeafReaderContext;
import com.naqqa.elasticsearch.search.similarity.Explanation;

import java.io.IOException;
import java.util.Arrays;

public final class PointRangeQuery extends Query {

    private final String field;
    private final byte[] lowerPoint;
    private final byte[] upperPoint;
    private final int numDims;
    private final int bytesPerDim;

    private PointRangeQuery(String field, byte[] lowerPoint, byte[] upperPoint, int numDims, int bytesPerDim) {
        this.field = field;
        this.lowerPoint = lowerPoint;
        this.upperPoint = upperPoint;
        this.numDims = numDims;
        this.bytesPerDim = bytesPerDim;
    }

    public static PointRangeQuery newIntRange(String field, int lower, int upper) {
        byte[] lo = new byte[4];
        byte[] hi = new byte[4];
        NumericUtils.intToSortableBytes(lower, lo, 0);
        NumericUtils.intToSortableBytes(upper, hi, 0);
        return new PointRangeQuery(field, lo, hi, 1, 4);
    }

    public static PointRangeQuery newLongRange(String field, long lower, long upper) {
        byte[] lo = new byte[8];
        byte[] hi = new byte[8];
        NumericUtils.longToSortableBytes(lower, lo, 0);
        NumericUtils.longToSortableBytes(upper, hi, 0);
        return new PointRangeQuery(field, lo, hi, 1, 8);
    }

    public static PointRangeQuery newFloatRange(String field, float lower, float upper) {
        byte[] lo = new byte[4];
        byte[] hi = new byte[4];
        NumericUtils.intToSortableBytes(NumericUtils.floatToSortableInt(lower), lo, 0);
        NumericUtils.intToSortableBytes(NumericUtils.floatToSortableInt(upper), hi, 0);
        return new PointRangeQuery(field, lo, hi, 1, 4);
    }

    public static PointRangeQuery newDoubleRange(String field, double lower, double upper) {
        byte[] lo = new byte[8];
        byte[] hi = new byte[8];
        NumericUtils.longToSortableBytes(NumericUtils.doubleToSortableLong(lower), lo, 0);
        NumericUtils.longToSortableBytes(NumericUtils.doubleToSortableLong(upper), hi, 0);
        return new PointRangeQuery(field, lo, hi, 1, 8);
    }

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) {
        return new PointRangeWeight(this, boost);
    }

    @Override
    public String toString() {
        return "PointRangeQuery(" + field + ")";
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof PointRangeQuery prq)) {
            return false;
        }
        return field.equals(prq.field) && numDims == prq.numDims && bytesPerDim == prq.bytesPerDim
            && Arrays.equals(lowerPoint, prq.lowerPoint) && Arrays.equals(upperPoint, prq.upperPoint);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(lowerPoint) * 31 + Arrays.hashCode(upperPoint) + field.hashCode();
    }

    private Relation compare(byte[][] cellMin, byte[][] cellMax) {
        boolean crosses = false;
        for (int d = 0; d < numDims; d++) {
            int off = d * bytesPerDim;
            if (NumericUtils.compareUnsigned(cellMin[d], 0, upperPoint, off, bytesPerDim) > 0
                || NumericUtils.compareUnsigned(cellMax[d], 0, lowerPoint, off, bytesPerDim) < 0) {
                return Relation.CELL_OUTSIDE_QUERY;
            }
            if (NumericUtils.compareUnsigned(cellMin[d], 0, lowerPoint, off, bytesPerDim) < 0
                || NumericUtils.compareUnsigned(cellMax[d], 0, upperPoint, off, bytesPerDim) > 0) {
                crosses = true;
            }
        }
        return crosses ? Relation.CELL_CROSSES_QUERY : Relation.CELL_INSIDE_QUERY;
    }

    private boolean valueInRange(byte[] packedValue) {
        for (int d = 0; d < numDims; d++) {
            int off = d * bytesPerDim;
            if (NumericUtils.compareUnsigned(packedValue, off, lowerPoint, off, bytesPerDim) < 0
                || NumericUtils.compareUnsigned(packedValue, off, upperPoint, off, bytesPerDim) > 0) {
                return false;
            }
        }
        return true;
    }

    private final class PointRangeWeight extends Weight {
        private final float boost;

        PointRangeWeight(Query query, float boost) {
            super(query);
            this.boost = boost;
        }

        private FixedBitSet buildMatches(LeafReaderContext context) throws IOException {
            BKDReader bkd = context.reader().pointValues(field);
            if (bkd == null) {
                return null;
            }
            FixedBitSet bits = new FixedBitSet(context.reader().maxDoc());
            bkd.intersect(new IntersectVisitor() {
                @Override
                public Relation compare(byte[][] cellMin, byte[][] cellMax) {
                    return PointRangeQuery.this.compare(cellMin, cellMax);
                }

                @Override
                public void visit(int docId) {
                    bits.set(docId);
                }

                @Override
                public void visit(int docId, byte[] packedValue) {
                    if (valueInRange(packedValue)) {
                        bits.set(docId);
                    }
                }
            });
            return bits;
        }

        @Override
        public Scorer scorer(LeafReaderContext context) throws IOException {
            FixedBitSet bits = buildMatches(context);
            if (bits == null || bits.cardinality() == 0) {
                return null;
            }
            return new BitSetScorer(this, bits, boost);
        }

        @Override
        public Explanation explain(LeafReaderContext context, int doc) throws IOException {
            FixedBitSet bits = buildMatches(context);
            if (bits != null && bits.get(doc)) {
                return Explanation.match(boost, "PointRangeQuery matches doc " + doc);
            }
            return Explanation.noMatch("doc " + doc + " is outside the point range");
        }
    }

    static final class BitSetScorer extends Scorer {
        private final FixedBitSet bits;
        private final float boost;
        private int doc = -1;

        BitSetScorer(Weight weight, FixedBitSet bits, float boost) {
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
}
