package com.naqqa.elasticsearch.search.bridge.function;

import com.naqqa.elasticsearch.common.util.FixedBitSet;
import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.LeafReaderContext;
import com.naqqa.elasticsearch.search.query.Query;
import com.naqqa.elasticsearch.search.query.ScoreMode;
import com.naqqa.elasticsearch.search.query.Scorer;
import com.naqqa.elasticsearch.search.query.Weight;
import com.naqqa.elasticsearch.search.similarity.Explanation;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

public final class CompositeFunctionScoreQuery extends Query {

    public enum ScoreCombine { MULTIPLY, SUM, AVG, FIRST, MAX, MIN }

    public enum BoostCombine { MULTIPLY, REPLACE, SUM, AVG, MAX, MIN }

    public record FunctionEntry(Query filter, FunctionSpec spec, Float weight) {
    }

    private final Query inner;
    private final List<FunctionEntry> functions;
    private final ScoreCombine scoreCombine;
    private final BoostCombine boostCombine;
    private final Float maxBoost;
    private final Float minScore;

    public CompositeFunctionScoreQuery(Query inner, List<FunctionEntry> functions, ScoreCombine scoreCombine,
                                        BoostCombine boostCombine, Float maxBoost, Float minScore) {
        this.inner = Objects.requireNonNull(inner);
        this.functions = List.copyOf(functions);
        this.scoreCombine = scoreCombine;
        this.boostCombine = boostCombine;
        this.maxBoost = maxBoost;
        this.minScore = minScore;
    }

    @Override
    public Query rewrite(IndexSearcher searcher) throws IOException {
        Query rewritten = inner.rewrite(searcher);
        return rewritten != inner
            ? new CompositeFunctionScoreQuery(rewritten, functions, scoreCombine, boostCombine, maxBoost, minScore)
            : this;
    }

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode mode, float boost) throws IOException {
        Weight innerWeight = inner.createWeight(searcher, ScoreMode.COMPLETE, boost);
        List<Weight> filterWeights = new java.util.ArrayList<>(functions.size());
        for (FunctionEntry fn : functions) {
            filterWeights.add(fn.filter() == null ? null : fn.filter().createWeight(searcher, ScoreMode.COMPLETE_NO_SCORES, 1f));
        }
        return new CompositeWeight(this, innerWeight, filterWeights);
    }

    @Override
    public String toString() {
        return "CompositeFunctionScoreQuery(" + inner + ", functions=" + functions.size() + ")";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CompositeFunctionScoreQuery q && inner.equals(q.inner) && functions.equals(q.functions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(inner, functions.size());
    }

    private final class CompositeWeight extends Weight {
        private final Weight innerWeight;
        private final List<Weight> filterWeights;

        CompositeWeight(Query query, Weight innerWeight, List<Weight> filterWeights) {
            super(query);
            this.innerWeight = innerWeight;
            this.filterWeights = filterWeights;
        }

        private FixedBitSet[] buildFilterBits(LeafReaderContext context) throws IOException {
            FixedBitSet[] result = new FixedBitSet[filterWeights.size()];
            int maxDoc = context.reader().maxDoc();
            for (int i = 0; i < filterWeights.size(); i++) {
                Weight fw = filterWeights.get(i);
                if (fw == null) {
                    continue;
                }
                Scorer s = fw.scorer(context);
                if (s == null) {
                    result[i] = new FixedBitSet(maxDoc);
                    continue;
                }
                FixedBitSet bits = new FixedBitSet(maxDoc);
                int doc;
                while ((doc = s.nextDoc()) != com.naqqa.elasticsearch.codec.DocIdSetIterator.NO_MORE_DOCS) {
                    bits.set(doc);
                }
                result[i] = bits;
            }
            return result;
        }

        @Override
        public Scorer scorer(LeafReaderContext context) throws IOException {
            Scorer innerScorer = innerWeight.scorer(context);
            if (innerScorer == null) {
                return null;
            }
            FixedBitSet[] filterBits = buildFilterBits(context);
            LeafDocLookup docLookup = new LeafDocLookup(context);
            return new CompositeScorer(this, innerScorer, filterBits, docLookup);
        }

        @Override
        public Explanation explain(LeafReaderContext context, int doc) throws IOException {
            Explanation innerExp = innerWeight.explain(context, doc);
            if (!innerExp.isMatch()) {
                return innerExp;
            }
            FixedBitSet[] filterBits = buildFilterBits(context);
            LeafDocLookup docLookup = new LeafDocLookup(context);
            docLookup.setDocId(doc);
            double combined = combineScore(innerExp.value(), doc, filterBits, docLookup);
            return Explanation.match((float) combined, "function score combining query score with functions");
        }
    }

    private double combineScore(float subScore, int doc, FixedBitSet[] filterBits, LeafDocLookup docLookup) {
        java.util.List<Double> applicable = new java.util.ArrayList<>();
        for (int i = 0; i < functions.size(); i++) {
            FunctionEntry fn = functions.get(i);
            boolean matches = filterBits[i] == null || (doc < filterBits[i].length() && filterBits[i].get(doc));
            if (!matches) {
                continue;
            }
            double v = fn.spec().evaluate(docLookup, subScore);
            if (fn.weight() != null) {
                v *= fn.weight();
            }
            applicable.add(v);
        }
        double funcScore;
        if (functions.isEmpty()) {
            funcScore = 1.0;
        } else if (applicable.isEmpty()) {
            funcScore = switch (scoreCombine) {
                case MULTIPLY -> 1.0;
                default -> 0.0;
            };
        } else {
            funcScore = switch (scoreCombine) {
                case MULTIPLY -> applicable.stream().reduce(1.0, (a, b) -> a * b);
                case SUM -> applicable.stream().mapToDouble(Double::doubleValue).sum();
                case AVG -> applicable.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
                case FIRST -> applicable.get(0);
                case MAX -> applicable.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
                case MIN -> applicable.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
            };
        }
        if (maxBoost != null) {
            funcScore = Math.min(funcScore, maxBoost);
        }
        double result = switch (boostCombine) {
            case MULTIPLY -> subScore * funcScore;
            case REPLACE -> funcScore;
            case SUM -> subScore + funcScore;
            case AVG -> (subScore + funcScore) / 2.0;
            case MAX -> Math.max(subScore, funcScore);
            case MIN -> Math.min(subScore, funcScore);
        };
        return result;
    }

    final class CompositeScorer extends Scorer {
        private final Scorer inner;
        private final FixedBitSet[] filterBits;
        private final LeafDocLookup docLookup;

        CompositeScorer(Weight weight, Scorer inner, FixedBitSet[] filterBits, LeafDocLookup docLookup) {
            super(weight);
            this.inner = inner;
            this.filterBits = filterBits;
            this.docLookup = docLookup;
        }

        private Float cachedScore;

        @Override
        public int docID() {
            return inner.docID();
        }

        @Override
        public int nextDoc() throws IOException {
            return doNext(inner.nextDoc());
        }

        @Override
        public int advance(int target) throws IOException {
            return doNext(inner.advance(target));
        }

        private int doNext(int doc) throws IOException {
            while (doc != NO_MORE_DOCS && minScore != null && computeScore(doc) < minScore) {
                doc = inner.nextDoc();
            }
            return doc;
        }

        private float computeScore(int doc) throws IOException {
            docLookup.setDocId(doc);
            float value = (float) combineScore(inner.score(), doc, filterBits, docLookup);
            cachedScore = value;
            return value;
        }

        @Override
        public long cost() {
            return inner.cost();
        }

        @Override
        public float score() throws IOException {
            if (cachedScore != null && inner.docID() == docID()) {
                float value = cachedScore;
                cachedScore = null;
                return value;
            }
            return computeScore(inner.docID());
        }
    }
}
