package com.naqqa.elasticsearch.search.similarity;

public final class IBSimilarity implements Similarity {

    private final double c;

    public IBSimilarity() {
        this(1.0);
    }

    public IBSimilarity(double c) {
        if (c <= 0) {
            throw new IllegalArgumentException("c must be > 0, got " + c);
        }
        this.c = c;
    }

    @Override
    public SimWeight computeWeight(CollectionStatistics collectionStats, TermStatistics... termStats) {
        long totalTermFreqSum = 0;
        for (TermStatistics ts : termStats) {
            totalTermFreqSum += ts.totalTermFreq();
        }
        double lambda = (double) Math.max(totalTermFreqSum, 1) / Math.max(collectionStats.docCount(), 1);
        return new IBWeight(lambda, collectionStats.averageFieldLength());
    }

    @Override
    public SimScorer simScorer(SimWeight weight) {
        return new IBScorer((IBWeight) weight, c);
    }

    private record IBWeight(double lambda, double avgdl) implements SimWeight {
    }

    private static final class IBScorer implements SimScorer {
        private final IBWeight weight;
        private final double c;

        IBScorer(IBWeight weight, double c) {
            this.weight = weight;
            this.c = c;
        }

        private double tfn(float freq, long norm) {
            double dl = norm <= 0 ? 1.0 : (double) norm;
            return freq * log2(1 + c * weight.avgdl() / dl);
        }

        private static double log2(double x) {
            return Math.log(x) / Math.log(2);
        }

        @Override
        public float score(float freq, long norm) {
            if (freq <= 0) {
                return 0f;
            }
            double tfn = tfn(freq, norm);
            return (float) log2(1.0 + tfn / weight.lambda());
        }

        @Override
        public Explanation explain(float freq, long norm, Explanation freqExplanation) {
            double tfn = tfn(freq, norm);
            float score = freq <= 0 ? 0f : (float) log2(1.0 + tfn / weight.lambda());
            return Explanation.match(score, "score(freq=" + freq + "), IB log-logistic model, computed as log2(1 + tfn/lambda) from:",
                Explanation.match((float) tfn, "tfn, normalized term frequency (Normalization H2)"),
                Explanation.match((float) weight.lambda(), "lambda, computed as totalTermFreq/docCount (LambdaTTF)"));
        }
    }
}
