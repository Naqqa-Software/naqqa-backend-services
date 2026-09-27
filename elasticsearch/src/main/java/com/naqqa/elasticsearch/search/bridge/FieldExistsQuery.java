package com.naqqa.elasticsearch.search.bridge;

import com.naqqa.elasticsearch.codec.fieldinfos.DocValuesType;
import com.naqqa.elasticsearch.codec.fieldinfos.FieldInfo;
import com.naqqa.elasticsearch.codec.postings.PostingsFlags;
import com.naqqa.elasticsearch.codec.terms.TermsEnum;
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

public final class FieldExistsQuery extends Query {

    private final String field;

    public FieldExistsQuery(String field) {
        this.field = Objects.requireNonNull(field);
    }

    public String field() {
        return field;
    }

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) {
        return new ExistsWeight(this, boost);
    }

    @Override
    public String toString() {
        return "FieldExistsQuery(" + field + ")";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FieldExistsQuery q && field.equals(q.field);
    }

    @Override
    public int hashCode() {
        return field.hashCode();
    }

    private FixedBitSet buildBits(LeafReaderContext context) throws IOException {
        LeafReader reader = context.reader();
        FieldInfo info = reader.fieldInfo(field);
        int maxDoc = reader.maxDoc();
        FixedBitSet bits = new FixedBitSet(maxDoc);
        boolean any = false;
        if (info != null && info.docValuesType() != DocValuesType.NONE) {
            switch (info.docValuesType()) {
                case NUMERIC -> {
                    var dv = reader.numericDocValues(field);
                    if (dv != null) {
                        for (int d = 0; d < maxDoc; d++) {
                            if (dv.advanceExact(d)) {
                                bits.set(d);
                                any = true;
                            }
                        }
                    }
                }
                case SORTED -> {
                    var dv = reader.sortedDocValues(field);
                    if (dv != null) {
                        for (int d = 0; d < maxDoc; d++) {
                            if (dv.advanceExact(d)) {
                                bits.set(d);
                                any = true;
                            }
                        }
                    }
                }
                case SORTED_NUMERIC -> {
                    var dv = reader.sortedNumericDocValues(field);
                    if (dv != null) {
                        for (int d = 0; d < maxDoc; d++) {
                            if (dv.advanceExact(d)) {
                                bits.set(d);
                                any = true;
                            }
                        }
                    }
                }
                case SORTED_SET -> {
                    var dv = reader.sortedSetDocValues(field);
                    if (dv != null) {
                        for (int d = 0; d < maxDoc; d++) {
                            if (dv.advanceExact(d)) {
                                bits.set(d);
                                any = true;
                            }
                        }
                    }
                }
                case BINARY -> {
                }
            }
        } else if (info != null && info.indexed()) {
            TermsEnum te = reader.terms(field);
            if (te != null) {
                byte[] t;
                while ((t = te.next()) != null) {
                    var postings = te.postings(PostingsFlags.DOCS_ONLY);
                    int doc;
                    while ((doc = postings.nextDoc()) != com.naqqa.elasticsearch.codec.DocIdSetIterator.NO_MORE_DOCS) {
                        bits.set(doc);
                        any = true;
                    }
                }
            }
        }
        return any ? bits : null;
    }

    private final class ExistsWeight extends Weight {
        private final float boost;

        ExistsWeight(Query query, float boost) {
            super(query);
            this.boost = boost;
        }

        @Override
        public Scorer scorer(LeafReaderContext context) throws IOException {
            FixedBitSet bits = buildBits(context);
            if (bits == null) {
                return null;
            }
            return new BitsScorer(this, bits, boost);
        }

        @Override
        public Explanation explain(LeafReaderContext context, int doc) throws IOException {
            FixedBitSet bits = buildBits(context);
            if (bits != null && doc < bits.length() && bits.get(doc)) {
                return Explanation.match(boost, "FieldExistsQuery matches doc " + doc);
            }
            return Explanation.noMatch("field [" + field + "] has no value for doc " + doc);
        }
    }

    static final class BitsScorer extends Scorer {
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
