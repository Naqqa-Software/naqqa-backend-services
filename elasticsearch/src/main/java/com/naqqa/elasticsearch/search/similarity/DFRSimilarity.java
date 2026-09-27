package com.naqqa.elasticsearch.search.similarity;

public final class DFRSimilarity implements Similarity {

    private final double c;

    public DFRSimilarity() {
        this(1.0);
    }

    public DFRSimilarity(double c) {
        if (c <= 0) {
            throw new IllegalArgumentException("c must be > 0, got " + c);
        }
        this.c = c;
    }

    @Override
    public SimWeight computeWeight(CollectionStatistics collectionStats, TermStatistics... termStats) {
        long docFreqSum = 0;
        for (TermStatistics ts : termStats) {
            docFreqSum += ts.docFreq();
        }
        return new DFRWeight(Math.max(collectionStats.docCount(), 1), docFreqSum, collectionStats.averageFieldLength());
    }

    @Override
    public SimScorer simScorer(SimWeight weight) {
        return new DFRScorer((DFRWeight) weight, c);
    }

    private record DFRWeight(long docCount, long docFreq, double avgdl) implements SimWeight {
    }

    private static final class DFRScorer implements SimScorer {
        private final DFRWeight weight;
        private final double c;

        DFRScorer(DFRWeight weight, double c) {
            this.weight = weight;
            this.c = c;
        }

        private double tfn(float freq, long norm) {
            double dl = norm <= 0 ? 1.0 : (double) norm;
            return freq * log2(1 + c * weight.avgdl() / dl);
        }

        private double basicModelIn(double tfn) {
            return tfn * log2((weight.docCount() + 1.0) / (weight.docFreq() + 0.5));
        }

        private static double afterEffectL(double tfn) {
            return 1.0 / (tfn + 1.0);
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
            double inf = basicModelIn(tfn);
            double ae = afterEffectL(tfn);
            return (float) (inf * (1.0 - ae));
        }

        @Override
        public Explanation explain(float freq, long norm, Explanation freqExplanation) {
            double tfn = tfn(freq, norm);
            double inf = basicModelIn(tfn);
            double ae = afterEffectL(tfn);
            float score = freq <= 0 ? 0f : (float) (inf * (1.0 - ae));
            return Explanation.match(score, "score(freq=" + freq + "), DFR In_L2 model, computed as basicModel(tfn) * (1 - afterEffect(tfn)) from:",
                Explanation.match((float) tfn, "tfn, normalized term frequency (Normalization H2)"),
                Explanation.match((float) inf, "basicModel In, computed as tfn * log2((N+1)/(n+0.5))"),
                Explanation.match((float) ae, "afterEffect L, computed as 1/(tfn+1)"));
        }
    }
}
