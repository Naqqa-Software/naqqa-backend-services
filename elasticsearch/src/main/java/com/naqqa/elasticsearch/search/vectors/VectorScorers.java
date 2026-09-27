package com.naqqa.elasticsearch.search.vectors;

public final class VectorScorers {

    private VectorScorers() {
    }

    public static RandomVectorScorer floatScorer(FloatVectorValues values, VectorSimilarity similarity, float[] query) {
        if (query.length != values.dimension()) {
            throw new IllegalArgumentException("query dimension " + query.length + " != field dimension " + values.dimension());
        }
        return new RandomVectorScorer() {
            @Override
            public float score(int ord) {
                return similarity.score(query, values.vectorValue(ord));
            }

            @Override
            public int maxOrd() {
                return values.size();
            }

            @Override
            public int ordToDoc(int ord) {
                return values.ordToDoc(ord);
            }
        };
    }

    public static RandomVectorScorer byteScorer(ByteVectorValues values, VectorSimilarity similarity, byte[] query) {
        boolean bits = values.elementType() == ElementType.BIT;
        int expected = bits ? (values.dimension() + 7) / 8 : values.dimension();
        if (query.length != expected) {
            throw new IllegalArgumentException("query byte length " + query.length + " != expected " + expected);
        }
        return new RandomVectorScorer() {
            @Override
            public float score(int ord) {
                byte[] v = values.vectorValue(ord);
                return bits ? VectorSimilarity.bitScore(query, v) : similarity.score(query, v);
            }

            @Override
            public int maxOrd() {
                return values.size();
            }

            @Override
            public int ordToDoc(int ord) {
                return values.ordToDoc(ord);
            }
        };
    }

    public static RandomVectorScorerSupplier floatSupplier(FloatVectorValues values, VectorSimilarity similarity) {
        return new RandomVectorScorerSupplier() {
            @Override
            public RandomVectorScorer scorer(int ord) {
                return floatScorer(values, similarity, values.vectorValue(ord));
            }

            @Override
            public int maxOrd() {
                return values.size();
            }
        };
    }

    public static RandomVectorScorerSupplier byteSupplier(ByteVectorValues values, VectorSimilarity similarity) {
        return new RandomVectorScorerSupplier() {
            @Override
            public RandomVectorScorer scorer(int ord) {
                return byteScorer(values, similarity, values.vectorValue(ord));
            }

            @Override
            public int maxOrd() {
                return values.size();
            }
        };
    }
}
