package com.naqqa.elasticsearch.search.similarity;

public final class BooleanSimilarity implements Similarity {

    @Override
    public SimWeight computeWeight(CollectionStatistics collectionStats, TermStatistics... termStats) {
        return EMPTY_WEIGHT;
    }

    @Override
    public SimScorer simScorer(SimWeight weight) {
        return SCORER;
    }

    private static final SimWeight EMPTY_WEIGHT = new SimWeight() {
    };

    private static final SimScorer SCORER = new SimScorer() {
        @Override
        public float score(float freq, long norm) {
            return freq > 0 ? 1f : 0f;
        }

        @Override
        public Explanation explain(float freq, long norm, Explanation freqExplanation) {
            float score = freq > 0 ? 1f : 0f;
            return Explanation.match(score, "score, presence of term, boolean similarity ignores tf/idf/norm");
        }
    };
}
