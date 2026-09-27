package com.naqqa.elasticsearch.search.similarity;

public final class BM25Similarity implements Similarity {

    private final float k1;
    private final float b;

    public BM25Similarity() {
        this(1.2f, 0.75f);
    }

    public BM25Similarity(float k1, float b) {
        if (k1 < 0 || Float.isNaN(k1)) {
            throw new IllegalArgumentException("k1 must be >= 0, got " + k1);
        }
        if (b < 0 || b > 1 || Float.isNaN(b)) {
            throw new IllegalArgumentException("b must be in [0,1], got " + b);
        }
        this.k1 = k1;
        this.b = b;
    }

    public static double idf(long docFreq, long docCount) {
        return Math.log(1.0 + (docCount - docFreq + 0.5) / (docFreq + 0.5));
    }

    @Override
    public SimWeight computeWeight(CollectionStatistics collectionStats, TermStatistics... termStats) {
        double idfSum = 0;
        StringBuilder idfExplain = new StringBuilder();
        for (TermStatistics ts : termStats) {
            double idf = idf(ts.docFreq(), Math.max(collectionStats.docCount(), 1));
            idfSum += idf;
            if (idfExplain.length() > 0) {
                idfExplain.append(", ");
            }
            idfExplain.append("idf(docFreq=").append(ts.docFreq()).append(", docCount=").append(collectionStats.docCount())
                .append(")=").append(idf);
        }
        double avgdl = collectionStats.averageFieldLength();
        return new BM25Weight((float) idfSum, (float) avgdl, idfExplain.toString());
    }

    @Override
    public SimScorer simScorer(SimWeight weight) {
        BM25Weight w = (BM25Weight) weight;
        return new BM25Scorer(w, k1, b);
    }

    private record BM25Weight(float idf, float avgdl, String idfExplain) implements SimWeight {
    }

    private static final class BM25Scorer implements SimScorer {
        private final BM25Weight weight;
        private final float k1;
        private final float b;

        BM25Scorer(BM25Weight weight, float k1, float b) {
            this.weight = weight;
            this.k1 = k1;
            this.b = b;
        }

        @Override
        public float score(float freq, long norm) {
            double dl = norm <= 0 ? 1.0 : (double) norm;
            double denom = freq + k1 * (1 - b + b * dl / Math.max(weight.avgdl(), 1e-9));
            return (float) (weight.idf() * ((freq * (k1 + 1)) / denom));
        }

        @Override
        public Explanation explain(float freq, long norm, Explanation freqExplanation) {
            double dl = norm <= 0 ? 1.0 : (double) norm;
            double denom = freq + k1 * (1 - b + b * dl / Math.max(weight.avgdl(), 1e-9));
            float tfNorm = (float) ((freq * (k1 + 1)) / denom);
            float score = weight.idf() * tfNorm;
            Explanation idfExplanation = Explanation.match(weight.idf(), "idf, computed as log(1 + (N - n + 0.5) / (n + 0.5)) from: " + weight.idfExplain());
            Explanation tfExplanation = Explanation.match(tfNorm, "tfNorm, computed as (freq * (k1 + 1)) / (freq + k1 * (1 - b + b * dl / avgdl)) from:",
                Explanation.match(freq, "termFreq=" + freq),
                Explanation.match(k1, "parameter k1"),
                Explanation.match(b, "parameter b"),
                Explanation.match((float) dl, "dl, length of field"),
                Explanation.match(weight.avgdl(), "avgdl, average length of field"));
            return Explanation.match(score, "score(freq=" + freq + "), computed as idf * tfNorm from:", idfExplanation, tfExplanation);
        }
    }
}
