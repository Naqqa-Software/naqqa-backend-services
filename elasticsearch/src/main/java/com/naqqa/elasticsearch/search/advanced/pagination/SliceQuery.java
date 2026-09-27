package com.naqqa.elasticsearch.search.advanced.pagination;

import com.naqqa.elasticsearch.codec.DocIdSetIterator;
import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.LeafReaderContext;
import com.naqqa.elasticsearch.search.query.Query;
import com.naqqa.elasticsearch.search.query.ScoreMode;
import com.naqqa.elasticsearch.search.query.Scorer;
import com.naqqa.elasticsearch.search.query.TwoPhaseIterator;
import com.naqqa.elasticsearch.search.query.Weight;
import com.naqqa.elasticsearch.search.similarity.Explanation;

import java.io.IOException;
import java.util.Objects;

public final class SliceQuery extends Query {

    private final Query inner;
    private final int id;
    private final int max;

    public SliceQuery(Query inner, int id, int max) {
        if (max <= 0 || id < 0 || id >= max) {
            throw new IllegalArgumentException("invalid slice id/max: " + id + "/" + max);
        }
        this.inner = Objects.requireNonNull(inner);
        this.id = id;
        this.max = max;
    }

    public Query inner() {
        return inner;
    }

    public int id() {
        return id;
    }

    public int max() {
        return max;
    }

    @Override
    public Query rewrite(IndexSearcher searcher) throws IOException {
        Query rewritten = inner.rewrite(searcher);
        if (rewritten != inner) {
            return new SliceQuery(rewritten, id, max);
        }
        return this;
    }

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) throws IOException {
        Weight innerWeight = inner.createWeight(searcher, scoreMode, boost);
        return new Weight(this) {
            @Override
            public Scorer scorer(LeafReaderContext context) throws IOException {
                Scorer innerScorer = innerWeight.scorer(context);
                if (innerScorer == null) {
                    return null;
                }
                int docBase = context.docBase();
                TwoPhaseIterator twoPhase = new TwoPhaseIterator(innerScorer) {
                    @Override
                    public boolean matches() {
                        return Math.floorMod(docBase + innerScorer.docID(), max) == id;
                    }

                    @Override
                    public float matchCost() {
                        return 1f;
                    }
                };
                return new SliceScorer(this, innerScorer, twoPhase);
            }

            @Override
            public Explanation explain(LeafReaderContext context, int doc) throws IOException {
                return innerWeight.explain(context, doc);
            }
        };
    }

    @Override
    public String toString() {
        return "SliceQuery(" + inner + ", id=" + id + ", max=" + max + ")";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SliceQuery sq && id == sq.id && max == sq.max && inner.equals(sq.inner);
    }

    @Override
    public int hashCode() {
        return Objects.hash(SliceQuery.class, inner, id, max);
    }

    private static final class SliceScorer extends Scorer {
        private final Scorer inner;
        private final DocIdSetIterator disi;

        SliceScorer(Weight weight, Scorer inner, TwoPhaseIterator twoPhase) {
            super(weight);
            this.inner = inner;
            this.disi = TwoPhaseIterator.asDocIdSetIterator(twoPhase);
        }

        @Override
        public int docID() {
            return disi.docID();
        }

        @Override
        public int nextDoc() throws IOException {
            return disi.nextDoc();
        }

        @Override
        public int advance(int target) throws IOException {
            return disi.advance(target);
        }

        @Override
        public long cost() {
            return disi.cost();
        }

        @Override
        public float score() throws IOException {
            return inner.score();
        }
    }
}
