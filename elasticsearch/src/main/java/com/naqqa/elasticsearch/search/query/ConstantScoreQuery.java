package com.naqqa.elasticsearch.search.query;

import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.LeafReaderContext;
import com.naqqa.elasticsearch.search.similarity.Explanation;

import java.io.IOException;
import java.util.Objects;

public final class ConstantScoreQuery extends Query {

    private final Query inner;

    public ConstantScoreQuery(Query inner) {
        this.inner = inner;
    }

    public Query inner() {
        return inner;
    }

    @Override
    public Query rewrite(IndexSearcher searcher) throws IOException {
        Query rewritten = inner.rewrite(searcher);
        if (rewritten != inner) {
            return new ConstantScoreQuery(rewritten);
        }
        return this;
    }

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) throws IOException {
        Weight innerWeight = inner.createWeight(searcher, ScoreMode.COMPLETE_NO_SCORES, 1f);
        return new ConstantScoreWeight(this, innerWeight, boost);
    }

    @Override
    public String toString() {
        return "ConstantScore(" + inner + ")";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ConstantScoreQuery csq && inner.equals(csq.inner);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ConstantScoreQuery.class, inner);
    }

    private static final class ConstantScoreWeight extends Weight {
        private final Weight innerWeight;
        private final float boost;

        ConstantScoreWeight(Query query, Weight innerWeight, float boost) {
            super(query);
            this.innerWeight = innerWeight;
            this.boost = boost;
        }

        @Override
        public Scorer scorer(LeafReaderContext context) throws IOException {
            Scorer innerScorer = innerWeight.scorer(context);
            if (innerScorer == null) {
                return null;
            }
            return new ConstantScoreScorer(this, innerScorer, boost);
        }

        @Override
        public Explanation explain(LeafReaderContext context, int doc) throws IOException {
            Explanation innerExplanation = innerWeight.explain(context, doc);
            if (!innerExplanation.isMatch()) {
                return Explanation.noMatch("no match on required clause", innerExplanation);
            }
            return Explanation.match(boost, "ConstantScore(" + innerWeight.getQuery() + "), boost applied to matching documents");
        }
    }

    private static final class ConstantScoreScorer extends Scorer {
        private final Scorer inner;
        private final float boost;

        ConstantScoreScorer(Weight weight, Scorer inner, float boost) {
            super(weight);
            this.inner = inner;
            this.boost = boost;
        }

        @Override
        public int docID() {
            return inner.docID();
        }

        @Override
        public int nextDoc() throws IOException {
            return inner.nextDoc();
        }

        @Override
        public int advance(int target) throws IOException {
            return inner.advance(target);
        }

        @Override
        public long cost() {
            return inner.cost();
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
