package com.naqqa.elasticsearch.search.vectors;

import java.util.Locale;

public enum VectorSimilarity {
    L2_NORM("l2_norm") {
        @Override
        public float score(float[] a, float[] b) {
            return 1f / (1f + VectorUtil.squareDistance(a, b));
        }

        @Override
        public float score(byte[] a, byte[] b) {
            return 1f / (1f + VectorUtil.squareDistance(a, b));
        }

        @Override
        public float similarity(float[] a, float[] b) {
            return VectorUtil.l2Distance(a, b);
        }

        @Override
        public float similarity(byte[] a, byte[] b) {
            return VectorUtil.l2Distance(a, b);
        }

        @Override
        public float similarityToScore(float similarity, ElementType elementType, int dims) {
            if (elementType == ElementType.BIT) {
                return (dims - similarity) / dims;
            }
            return 1f / (1f + similarity * similarity);
        }

        @Override
        public float scoreToSimilarity(float score, ElementType elementType, int dims) {
            if (elementType == ElementType.BIT) {
                return dims - score * dims;
            }
            if (score <= 0) {
                return Float.POSITIVE_INFINITY;
            }
            return (float) Math.sqrt(Math.max(0.0, 1.0 / score - 1.0));
        }
    },
    COSINE("cosine") {
        @Override
        public float score(float[] a, float[] b) {
            return Math.max((1f + VectorUtil.cosine(a, b)) / 2f, 0f);
        }

        @Override
        public float score(byte[] a, byte[] b) {
            return (1f + VectorUtil.cosine(a, b)) / 2f;
        }

        @Override
        public float similarity(float[] a, float[] b) {
            return VectorUtil.cosine(a, b);
        }

        @Override
        public float similarity(byte[] a, byte[] b) {
            return VectorUtil.cosine(a, b);
        }

        @Override
        public float similarityToScore(float similarity, ElementType elementType, int dims) {
            requireNotBit(elementType);
            return (1f + similarity) / 2f;
        }

        @Override
        public float scoreToSimilarity(float score, ElementType elementType, int dims) {
            requireNotBit(elementType);
            return 2f * score - 1f;
        }
    },
    DOT_PRODUCT("dot_product") {
        @Override
        public float score(float[] a, float[] b) {
            return Math.max((1f + VectorUtil.dotProduct(a, b)) / 2f, 0f);
        }

        @Override
        public float score(byte[] a, byte[] b) {
            return 0.5f + VectorUtil.dotProduct(a, b) / (float) (a.length * (1 << 15));
        }

        @Override
        public float similarity(float[] a, float[] b) {
            return VectorUtil.dotProduct(a, b);
        }

        @Override
        public float similarity(byte[] a, byte[] b) {
            return VectorUtil.dotProduct(a, b);
        }

        @Override
        public float similarityToScore(float similarity, ElementType elementType, int dims) {
            requireNotBit(elementType);
            if (elementType == ElementType.BYTE) {
                return 0.5f + similarity / (float) (dims * (1 << 15));
            }
            return (1f + similarity) / 2f;
        }

        @Override
        public float scoreToSimilarity(float score, ElementType elementType, int dims) {
            requireNotBit(elementType);
            if (elementType == ElementType.BYTE) {
                return (score - 0.5f) * (float) (dims * (1 << 15));
            }
            return 2f * score - 1f;
        }
    },
    MAX_INNER_PRODUCT("max_inner_product") {
        @Override
        public float score(float[] a, float[] b) {
            return VectorUtil.scaleMaxInnerProductScore(VectorUtil.dotProduct(a, b));
        }

        @Override
        public float score(byte[] a, byte[] b) {
            return VectorUtil.scaleMaxInnerProductScore(VectorUtil.dotProduct(a, b));
        }

        @Override
        public float similarity(float[] a, float[] b) {
            return VectorUtil.dotProduct(a, b);
        }

        @Override
        public float similarity(byte[] a, byte[] b) {
            return VectorUtil.dotProduct(a, b);
        }

        @Override
        public float similarityToScore(float similarity, ElementType elementType, int dims) {
            requireNotBit(elementType);
            return VectorUtil.scaleMaxInnerProductScore(similarity);
        }

        @Override
        public float scoreToSimilarity(float score, ElementType elementType, int dims) {
            requireNotBit(elementType);
            if (score < 1f) {
                if (score <= 0f) {
                    return Float.NEGATIVE_INFINITY;
                }
                return 1f - 1f / score;
            }
            return score - 1f;
        }
    };

    private final String esName;

    VectorSimilarity(String esName) {
        this.esName = esName;
    }

    public String esName() {
        return esName;
    }

    public abstract float score(float[] a, float[] b);

    public abstract float score(byte[] a, byte[] b);

    public abstract float similarity(float[] a, float[] b);

    public abstract float similarity(byte[] a, byte[] b);

    public abstract float similarityToScore(float similarity, ElementType elementType, int dims);

    public abstract float scoreToSimilarity(float score, ElementType elementType, int dims);

    public float score(byte[] a, byte[] b, ElementType elementType) {
        if (elementType == ElementType.BIT) {
            return bitScore(a, b);
        }
        return score(a, b);
    }

    public static float bitScore(byte[] a, byte[] b) {
        int bits = a.length * 8;
        return (bits - VectorUtil.hamming(a, b)) / (float) bits;
    }

    public static float bitSimilarity(byte[] a, byte[] b) {
        return VectorUtil.hamming(a, b);
    }

    private static void requireNotBit(ElementType elementType) {
        if (elementType == ElementType.BIT) {
            throw new IllegalArgumentException("similarity is not supported for element_type [bit]");
        }
    }

    public static VectorSimilarity fromString(String value) {
        String v = value.toLowerCase(Locale.ROOT);
        for (VectorSimilarity s : values()) {
            if (s.esName.equals(v)) {
                return s;
            }
        }
        throw new IllegalArgumentException("unknown vector similarity [" + value + "]");
    }
}
