package com.naqqa.elasticsearch.search.bridge;

import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.LeafReaderContext;
import com.naqqa.elasticsearch.search.query.Query;
import com.naqqa.elasticsearch.search.query.ScoreMode;
import com.naqqa.elasticsearch.search.query.Scorer;
import com.naqqa.elasticsearch.search.query.Weight;
import com.naqqa.elasticsearch.search.similarity.Explanation;

import java.io.IOException;
import java.util.Objects;

public final class BoostingQuery extends Query {

    private final Query positive;
    private final Query negative;
    private final float negativeBoost;

    public BoostingQuery(Query positive, Query negative, float negativeBoost) {
        this.positive = Objects.requireNonNull(positive);
        this.negative = Objects.requireNonNull(negative);
        this.negativeBoost = negativeBoost;
    }

    public Query positive() {
        return positive;
    }

    public Query negative() {
        return negative;
    }

    public float negativeBoost() {
        return negativeBoost;
    }

    @Override
    public Query rewrite(IndexSearcher searcher) throws IOException {
        Query rp = positive.rewrite(searcher);
        Query rn = negative.rewrite(searcher);
        if (rp != positive || rn != negative) {
            return new BoostingQuery(rp, rn, negativeBoost);
        }
        return this;
    }

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) throws IOException {
        Weight positiveWeight = positive.createWeight(searcher, ScoreMode.COMPLETE, boost);
        Weight negativeWeight = negative.createWeight(searcher, ScoreMode.COMPLETE_NO_SCORES, 1f);
        return new BoostingWeight(this, positiveWeight, negativeWeight, negativeBoost);
    }

    @Override
    public String toString() {
        return "BoostingQuery(+" + positive + " -" + negative + "^" + negativeBoost + ")";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof BoostingQuery q && positive.equals(q.positive) && negative.equals(q.negative)
            && negativeBoost == q.negativeBoost;
    }

    @Override
    public int hashCode() {
        return Objects.hash(positive, negative, negativeBoost);
    }

    private static final class BoostingWeight extends Weight {
        private final Weight positiveWeight;
        private final Weight negativeWeight;
        private final float negativeBoost;

        BoostingWeight(Query query, Weight positiveWeight, Weight negativeWeight, float negativeBoost) {
            super(query);
            this.positiveWeight = positiveWeight;
            this.negativeWeight = negativeWeight;
            this.negativeBoost = negativeBoost;
        }

        @Override
        public Scorer scorer(LeafReaderContext context) throws IOException {
            Scorer positiveScorer = positiveWeight.scorer(context);
            if (positiveScorer == null) {
                return null;
            }
            Scorer negativeScorer = negativeWeight.scorer(context);
            if (negativeScorer == null) {
                return positiveScorer;
            }
            return new BoostingScorer(this, positiveScorer, negativeScorer, negativeBoost);
        }

        @Override
        public Explanation explain(LeafReaderContext context, int doc) throws IOException {
            Explanation positiveExp = positiveWeight.explain(context, doc);
            if (!positiveExp.isMatch()) {
                return positiveExp;
            }
            Explanation negativeExp = negativeWeight.explain(context, doc);
            if (negativeExp.isMatch()) {
                float value = positiveExp.value() * negativeBoost;
                return Explanation.match(value, "product of:", positiveExp, Explanation.match(negativeBoost, "negativeBoost"));
            }
            return positiveExp;
        }
    }

    static final class BoostingScorer extends Scorer {
        private final Scorer positive;
        private final Scorer negative;
        private final float negativeBoost;

        BoostingScorer(Weight weight, Scorer positive, Scorer negative, float negativeBoost) {
            super(weight);
            this.positive = positive;
            this.negative = negative;
            this.negativeBoost = negativeBoost;
        }

        @Override
        public int docID() {
            return positive.docID();
        }

        @Override
        public int nextDoc() throws IOException {
            return positive.nextDoc();
        }

        @Override
        public int advance(int target) throws IOException {
            return positive.advance(target);
        }

        @Override
        public long cost() {
            return positive.cost();
        }

        @Override
        public float score() throws IOException {
            float score = positive.score();
            int doc = positive.docID();
            if (negative.docID() < doc) {
                negative.advance(doc);
            }
            if (negative.docID() == doc) {
                score *= negativeBoost;
            }
            return score;
        }

        @Override
        public float getMaxScore(int upTo) throws IOException {
            return positive.getMaxScore(upTo);
        }
    }
}
