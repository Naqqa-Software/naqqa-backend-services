package com.naqqa.elasticsearch.search.query;

import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.LeafReaderContext;
import com.naqqa.elasticsearch.search.similarity.Explanation;

import java.io.IOException;

public final class FunctionScoreQuery extends Query {

    public enum CombineFunction { MULTIPLY, REPLACE, SUM, AVG, MAX, MIN }

    private final Query inner;
    private final ScoreFunction function;
    private final CombineFunction combine;

    public FunctionScoreQuery(Query inner, ScoreFunction function, CombineFunction combine) {
        this.inner = inner;
        this.function = function;
        this.combine = combine;
    }

    public Query inner() {
        return inner;
    }

    public ScoreFunction function() {
        return function;
    }

    public CombineFunction combine() {
        return combine;
    }

    static float combine(CombineFunction combine, float subScore, double funcScore) {
        return switch (combine) {
            case MULTIPLY -> (float) (subScore * funcScore);
            case REPLACE -> (float) funcScore;
            case SUM -> (float) (subScore + funcScore);
            case AVG -> (float) ((subScore + funcScore) / 2.0);
            case MAX -> (float) Math.max(subScore, funcScore);
            case MIN -> (float) Math.min(subScore, funcScore);
        };
    }

    @Override
    public Query rewrite(IndexSearcher searcher) throws IOException {
        Query rewritten = inner.rewrite(searcher);
        return rewritten != inner ? new FunctionScoreQuery(rewritten, function, combine) : this;
    }

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) throws IOException {
        Weight innerWeight = inner.createWeight(searcher, ScoreMode.COMPLETE, boost);
        return new FunctionScoreWeight(this, innerWeight, function, combine);
    }

    @Override
    public String toString() {
        return "FunctionScoreQuery(" + inner + ", " + combine + ")";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FunctionScoreQuery fsq && inner.equals(fsq.inner) && combine == fsq.combine && function == fsq.function;
    }

    @Override
    public int hashCode() {
        return inner.hashCode() * 31 + combine.hashCode();
    }

    private static final class FunctionScoreWeight extends Weight {
        private final Weight innerWeight;
        private final ScoreFunction function;
        private final CombineFunction combine;

        FunctionScoreWeight(Query query, Weight innerWeight, ScoreFunction function, CombineFunction combine) {
            super(query);
            this.innerWeight = innerWeight;
            this.function = function;
            this.combine = combine;
        }

        @Override
        public Scorer scorer(LeafReaderContext context) throws IOException {
            Scorer innerScorer = innerWeight.scorer(context);
            if (innerScorer == null) {
                return null;
            }
            return new FunctionScoreScorer(this, innerScorer, function, combine);
        }

        @Override
        public Explanation explain(LeafReaderContext context, int doc) throws IOException {
            Explanation innerExplanation = innerWeight.explain(context, doc);
            if (!innerExplanation.isMatch()) {
                return innerExplanation;
            }
            double funcScore = function.score(doc, innerExplanation.value());
            float combined = combine(combine, innerExplanation.value(), funcScore);
            Explanation functionExplanation = function.explain(doc, innerExplanation.value(), innerExplanation);
            return Explanation.match(combined, "function score (" + combine + "), combining:", innerExplanation, functionExplanation);
        }
    }

    private static final class FunctionScoreScorer extends Scorer {
        private final Scorer inner;
        private final ScoreFunction function;
        private final CombineFunction combine;

        FunctionScoreScorer(Weight weight, Scorer inner, ScoreFunction function, CombineFunction combine) {
            super(weight);
            this.inner = inner;
            this.function = function;
            this.combine = combine;
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
        public float score() throws IOException {
            float subScore = inner.score();
            double funcScore = function.score(docID(), subScore);
            return combine(combine, subScore, funcScore);
        }
    }
}
