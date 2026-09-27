package com.naqqa.elasticsearch.search.similarity;

public final class ClassicSimilarity implements Similarity {

    public static float tf(float freq) {
        return (float) Math.sqrt(freq);
    }

    public static float idf(long docFreq, long docCount) {
        return (float) (Math.log((docCount + 1.0) / (docFreq + 1.0)) + 1.0);
    }

    @Override
    public SimWeight computeWeight(CollectionStatistics collectionStats, TermStatistics... termStats) {
        double idfSum = 0;
        StringBuilder explain = new StringBuilder();
        for (TermStatistics ts : termStats) {
            float idf = idf(ts.docFreq(), Math.max(collectionStats.docCount(), 1));
            idfSum += idf;
            if (explain.length() > 0) {
                explain.append(", ");
            }
            explain.append("idf(docFreq=").append(ts.docFreq()).append(", docCount=").append(collectionStats.docCount()).append(")=").append(idf);
        }
        return new ClassicWeight((float) idfSum, explain.toString());
    }

    @Override
    public SimScorer simScorer(SimWeight weight) {
        return new ClassicScorer((ClassicWeight) weight);
    }

    private record ClassicWeight(float idf, String idfExplain) implements SimWeight {
    }

    private static final class ClassicScorer implements SimScorer {
        private final ClassicWeight weight;

        ClassicScorer(ClassicWeight weight) {
            this.weight = weight;
        }

        private static float lengthNorm(long norm) {
            long length = Math.max(norm, 1);
            return (float) (1.0 / Math.sqrt(length));
        }

        @Override
        public float score(float freq, long norm) {
            return tf(freq) * weight.idf() * weight.idf() * lengthNorm(norm);
        }

        @Override
        public Explanation explain(float freq, long norm, Explanation freqExplanation) {
            float tf = tf(freq);
            float ln = lengthNorm(norm);
            float score = tf * weight.idf() * weight.idf() * ln;
            return Explanation.match(score, "score(freq=" + freq + "), computed as tf * idf^2 * lengthNorm from:",
                Explanation.match(tf, "tf, computed as sqrt(freq)"),
                Explanation.match(weight.idf(), "idf, from: " + weight.idfExplain()),
                Explanation.match(ln, "lengthNorm, computed as 1/sqrt(fieldLength), fieldLength=" + norm));
        }
    }
}
