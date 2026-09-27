package com.naqqa.elasticsearch.search.vectors;

public final class VectorScriptFunctions {

    private VectorScriptFunctions() {
    }

    public static double cosineSimilarity(float[] query, float[] docVector) {
        return VectorUtil.cosine(query, docVector);
    }

    public static double cosineSimilarity(byte[] query, byte[] docVector) {
        return VectorUtil.cosine(query, docVector);
    }

    public static double dotProduct(float[] query, float[] docVector) {
        return VectorUtil.dotProduct(query, docVector);
    }

    public static double dotProduct(byte[] query, byte[] docVector) {
        return VectorUtil.dotProduct(query, docVector);
    }

    public static double l1norm(float[] query, float[] docVector) {
        return VectorUtil.l1Distance(query, docVector);
    }

    public static double l1norm(byte[] query, byte[] docVector) {
        return VectorUtil.l1Distance(query, docVector);
    }

    public static double l2norm(float[] query, float[] docVector) {
        return Math.sqrt(VectorUtil.squareDistance(query, docVector));
    }

    public static double l2norm(byte[] query, byte[] docVector) {
        return Math.sqrt(VectorUtil.squareDistance(query, docVector));
    }

    public static double hamming(byte[] query, byte[] docVector) {
        return VectorUtil.hamming(query, docVector);
    }

    public static double l1normBits(byte[] query, byte[] docBits) {
        return VectorUtil.hamming(query, docBits);
    }

    public static double l2normBits(byte[] query, byte[] docBits) {
        return Math.sqrt(VectorUtil.hamming(query, docBits));
    }

    public static double dotProductBits(float[] query, byte[] docBits) {
        if (query.length != docBits.length * 8) {
            throw new IllegalArgumentException("query dimension " + query.length + " must equal bit dimension " + docBits.length * 8);
        }
        double sum = 0;
        for (int i = 0; i < query.length; i++) {
            if ((docBits[i >> 3] & (0x80 >>> (i & 7))) != 0) {
                sum += query[i];
            }
        }
        return sum;
    }

    public static double dotProductBits(byte[] query, byte[] docBits) {
        if (query.length == docBits.length) {
            long and = 0;
            for (int i = 0; i < query.length; i++) {
                and += Integer.bitCount((query[i] & docBits[i]) & 0xFF);
            }
            return and;
        }
        if (query.length != docBits.length * 8) {
            throw new IllegalArgumentException("query dimension " + query.length + " must equal bit dimension " + docBits.length * 8);
        }
        long sum = 0;
        for (int i = 0; i < query.length; i++) {
            if ((docBits[i >> 3] & (0x80 >>> (i & 7))) != 0) {
                sum += query[i];
            }
        }
        return sum;
    }

    public static double cosineSimilarity(float[] query, byte[] docVector) {
        double dot = 0;
        double qn = 0;
        double dn = 0;
        if (query.length != docVector.length) {
            throw new IllegalArgumentException("vector dimensions differ: " + query.length + " != " + docVector.length);
        }
        for (int i = 0; i < query.length; i++) {
            dot += query[i] * docVector[i];
            qn += query[i] * query[i];
            dn += docVector[i] * docVector[i];
        }
        return dot / Math.sqrt(qn * dn);
    }

    public static double dotProduct(float[] query, byte[] docVector) {
        if (query.length != docVector.length) {
            throw new IllegalArgumentException("vector dimensions differ: " + query.length + " != " + docVector.length);
        }
        double dot = 0;
        for (int i = 0; i < query.length; i++) {
            dot += query[i] * docVector[i];
        }
        return dot;
    }
}
