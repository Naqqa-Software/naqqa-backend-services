package com.naqqa.elasticsearch.search.similarity;

public final class LMJelinekMercerSimilarity implements Similarity {

    private final double lambda;

    public LMJelinekMercerSimilarity() {
        this(0.1);
    }

    public LMJelinekMercerSimilarity(double lambda) {
        if (lambda <= 0 || lambda >= 1) {
            throw new IllegalArgumentException("lambda must be in (0,1), got " + lambda);
        }
        this.lambda = lambda;
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
        return new LMJelinekMercerScorer((LMWeight) weight, lambda);
    }

    private record LMWeight(double collectionProbability) implements SimWeight {
    }

    private static final class LMJelinekMercerScorer implements SimScorer {
        private final LMWeight weight;
        private final double lambda;

        LMJelinekMercerScorer(LMWeight weight, double lambda) {
            this.weight = weight;
            this.lambda = lambda;
        }

        @Override
        public float score(float freq, long norm) {
            if (freq <= 0) {
                return 0f;
            }
            double dl = norm <= 0 ? 1.0 : (double) norm;
            double p = weight.collectionProbability();
            double score = Math.log(1.0 + ((1.0 - lambda) * freq / dl) / (lambda * p));
            return (float) Math.max(score, 0.0);
        }

        @Override
        public Explanation explain(float freq, long norm, Explanation freqExplanation) {
            double dl = norm <= 0 ? 1.0 : (double) norm;
            double p = weight.collectionProbability();
            double raw = freq <= 0 ? 0.0 : Math.log(1.0 + ((1.0 - lambda) * freq / dl) / (lambda * p));
            float score = (float) Math.max(raw, 0.0);
            return Explanation.match(score, "score(freq=" + freq + "), LM Jelinek-Mercer, computed as log(1 + ((1-lambda)*tf/dl) / (lambda*Pc)) from:",
                Explanation.match((float) p, "Pc, collection probability of term = totalTermFreq/sumTotalTermFreq"),
                Explanation.match((float) lambda, "lambda, Jelinek-Mercer smoothing parameter"),
                Explanation.match((float) dl, "dl, length of field"));
        }
    }
}
