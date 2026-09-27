package com.naqqa.elasticsearch.node.search;

import com.naqqa.elasticsearch.codec.DocIdSetIterator;
import com.naqqa.elasticsearch.codec.NumericUtils;
import com.naqqa.elasticsearch.codec.docvalues.NumericDocValuesReader;
import com.naqqa.elasticsearch.codec.docvalues.SortedNumericDocValuesReader;
import com.naqqa.elasticsearch.common.util.FixedBitSet;
import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.LeafReader;
import com.naqqa.elasticsearch.search.execution.LeafReaderContext;
import com.naqqa.elasticsearch.search.query.Query;
import com.naqqa.elasticsearch.search.query.ScoreMode;
import com.naqqa.elasticsearch.search.query.Scorer;
import com.naqqa.elasticsearch.search.query.Weight;
import com.naqqa.elasticsearch.search.similarity.Explanation;

import java.io.IOException;
import java.util.Objects;

public final class DocValuesRangeQuery extends Query {

    public enum Encoding { LONG, DOUBLE, FLOAT, SCALED }

    private final String field;
    private final Encoding encoding;
    private final long longLower;
    private final long longUpper;
    private final double doubleLower;
    private final double doubleUpper;
    private final double scalingFactor;

    private DocValuesRangeQuery(String field, Encoding encoding, long longLower, long longUpper, double doubleLower,
                                double doubleUpper, double scalingFactor) {
        this.field = Objects.requireNonNull(field);
        this.encoding = encoding;
        this.longLower = longLower;
        this.longUpper = longUpper;
        this.doubleLower = doubleLower;
        this.doubleUpper = doubleUpper;
        this.scalingFactor = scalingFactor;
    }

    public static DocValuesRangeQuery longRange(String field, long lowerInclusive, long upperInclusive) {
        return new DocValuesRangeQuery(field, Encoding.LONG, lowerInclusive, upperInclusive, 0, 0, 1);
    }

    public static DocValuesRangeQuery doubleRange(String field, Encoding encoding, double lowerInclusive, double upperInclusive,
                                                  double scalingFactor) {
        return new DocValuesRangeQuery(field, encoding, 0, 0, lowerInclusive, upperInclusive, scalingFactor);
    }

    public String field() {
        return field;
    }

    boolean matches(long raw) {
        return switch (encoding) {
            case LONG -> raw >= longLower && raw <= longUpper;
            case DOUBLE -> {
                double v = NumericUtils.sortableLongToDouble(raw);
                yield v >= doubleLower && v <= doubleUpper;
            }
            case FLOAT -> {
                double v = NumericUtils.sortableIntToFloat((int) raw);
                yield v >= doubleLower && v <= doubleUpper;
            }
            case SCALED -> {
                double v = raw / scalingFactor;
                yield v >= doubleLower && v <= doubleUpper;
            }
        };
    }

    private FixedBitSet buildBits(LeafReaderContext context) throws IOException {
        LeafReader reader = context.reader();
        int maxDoc = reader.maxDoc();
        FixedBitSet bits = new FixedBitSet(Math.max(1, maxDoc));
        boolean any = false;
        NumericDocValuesReader single = reader.numericDocValues(field);
        if (single != null) {
            for (int d = 0; d < maxDoc; d++) {
                if (reader.isLive(d) && single.advanceExact(d) && matches(single.longValue())) {
                    bits.set(d);
                    any = true;
                }
            }
        }
        SortedNumericDocValuesReader multi = reader.sortedNumericDocValues(field);
        if (multi != null) {
            for (int d = 0; d < maxDoc; d++) {
                if (!reader.isLive(d) || !multi.advanceExact(d)) {
                    continue;
                }
                int count = multi.docValueCount();
                for (int i = 0; i < count; i++) {
                    if (matches(multi.nextValue())) {
                        bits.set(d);
                        any = true;
                        break;
                    }
                }
            }
        }
        return any ? bits : null;
    }

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) {
        return new Weight(this) {
            @Override
            public Scorer scorer(LeafReaderContext context) throws IOException {
                FixedBitSet bits = buildBits(context);
                return bits == null ? null : new BitsScorer(this, bits, boost);
            }

            @Override
            public Explanation explain(LeafReaderContext context, int doc) throws IOException {
                FixedBitSet bits = buildBits(context);
                if (bits != null && doc < bits.length() && bits.get(doc)) {
                    return Explanation.match(boost, DocValuesRangeQuery.this.toString());
                }
                return Explanation.noMatch("no matching value for field [" + field + "] in doc " + doc);
            }
        };
    }

    @Override
    public String toString() {
        return encoding == Encoding.LONG ? field + ":[" + longLower + " TO " + longUpper + "]"
            : field + ":[" + doubleLower + " TO " + doubleUpper + "]";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DocValuesRangeQuery q && field.equals(q.field) && encoding == q.encoding
            && longLower == q.longLower && longUpper == q.longUpper && Double.compare(doubleLower, q.doubleLower) == 0
            && Double.compare(doubleUpper, q.doubleUpper) == 0 && Double.compare(scalingFactor, q.scalingFactor) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(field, encoding, longLower, longUpper, doubleLower, doubleUpper, scalingFactor);
    }

    private static final class BitsScorer extends Scorer {
        private final FixedBitSet bits;
        private final float boost;
        private int doc = -1;

        BitsScorer(Weight weight, FixedBitSet bits, float boost) {
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
                doc = DocIdSetIterator.NO_MORE_DOCS;
                return doc;
            }
            int next = bits.nextSetBit(target);
            doc = next < 0 ? DocIdSetIterator.NO_MORE_DOCS : next;
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
