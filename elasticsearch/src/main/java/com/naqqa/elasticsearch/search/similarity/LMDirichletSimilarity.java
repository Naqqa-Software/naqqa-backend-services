package com.naqqa.elasticsearch.search.similarity;

public final class LMDirichletSimilarity implements Similarity {

    private final double mu;

    public LMDirichletSimilarity() {
        this(2000.0);
    }

    public LMDirichletSimilarity(double mu) {
        if (mu < 0) {
            throw new IllegalArgumentException("mu must be >= 0, got " + mu);
        }
        this.mu = mu;
    }

    @Override
    public SimWeight computeWeight(CollectionStatistics collectionStats, TermStatistics... termStats) {
        long totalTermFreqSum = 0;
        for (TermStatistics ts : termStats) {
            totalTermFreqSum += ts.totalTermFreq();
        }
        double collectionProbability = (double) Math.max(totalTermFreqSum, 1)
            / Math.max(collectionStats.sumTotalTermFreq(), 1);
        return new LMWeight(collectionProbability);
    }

    @Override
    public SimScorer simScorer(SimWeight weight) {
        return new LMDirichletScorer((LMWeight) weight, mu);
    }

    private record LMWeight(double collectionProbability) implements SimWeight {
    }

    private static final class LMDirichletScorer implements SimScorer {
        private final LMWeight weight;
        private final double mu;

        LMDirichletScorer(LMWeight weight, double mu) {
            this.weight = weight;
            this.mu = mu;
        }

        @Override
        public float score(float freq, long norm) {
            double dl = norm <= 0 ? 1.0 : (double) norm;
            double p = weight.collectionProbability();
            double score = Math.log(1.0 + freq / (mu * p)) + Math.log(mu / (dl + mu));
            return (float) Math.max(score, 0.0);
        }

        @Override
        public Explanation explain(float freq, long norm, Explanation freqExplanation) {
            double dl = norm <= 0 ? 1.0 : (double) norm;
            double p = weight.collectionProbability();
            double raw = Math.log(1.0 + freq / (mu * p)) + Math.log(mu / (dl + mu));
            float score = (float) Math.max(raw, 0.0);
            return Explanation.match(score, "score(freq=" + freq + "), LM Dirichlet, computed as log(1 + tf/(mu*Pc)) + log(mu/(dl+mu)) from:",
                Explanation.match((float) p, "Pc, collection probability of term = totalTermFreq/sumTotalTermFreq"),
                Explanation.match((float) mu, "mu, Dirichlet prior smoothing parameter"),
                Explanation.match((float) dl, "dl, length of field"));
        }
    }
}
