package com.naqqa.elasticsearch.search.vectors;

public final class VectorUtil {

    public static final String DISABLE_SIMD_PROPERTY = "naqqa.vectors.simd.disabled";
    private static final String PANAMA_CLASS = "com.naqqa.elasticsearch.search.vectors.PanamaVectorUtilSupport";
    private static final VectorUtilSupport SCALAR = new ScalarVectorUtilSupport();
    private static final VectorUtilSupport SIMD = loadSimd();
    private static final VectorUtilSupport IMPL = SIMD != null && !Boolean.getBoolean(DISABLE_SIMD_PROPERTY) ? SIMD : SCALAR;

    private VectorUtil() {
    }

    private static VectorUtilSupport loadSimd() {
        try {
            if (ModuleLayer.boot().findModule("jdk.incubator.vector").isEmpty()) {
                return null;
            }
            Class<?> type = Class.forName(PANAMA_CLASS);
            return (VectorUtilSupport) type.getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            return null;
        }
    }

    public static VectorUtilSupport support() {
        return IMPL;
    }

    public static VectorUtilSupport scalarSupport() {
        return SCALAR;
    }

    public static VectorUtilSupport simdSupport() {
        return SIMD;
    }

    public static boolean isSimdEnabled() {
        return IMPL != SCALAR;
    }

    private static void checkDims(int a, int b) {
        if (a != b) {
            throw new IllegalArgumentException("vector dimensions differ: " + a + " != " + b);
        }
    }

    public static float dotProduct(float[] a, float[] b) {
        checkDims(a.length, b.length);
        return IMPL.dotProduct(a, b);
    }

    public static float cosine(float[] a, float[] b) {
        checkDims(a.length, b.length);
        return IMPL.cosine(a, b);
    }

    public static float squareDistance(float[] a, float[] b) {
        checkDims(a.length, b.length);
        return IMPL.squareDistance(a, b);
    }

    public static float l2Distance(float[] a, float[] b) {
        return (float) Math.sqrt(squareDistance(a, b));
    }

    public static float l1Distance(float[] a, float[] b) {
        checkDims(a.length, b.length);
        return IMPL.l1Distance(a, b);
    }

    public static int dotProduct(byte[] a, byte[] b) {
        checkDims(a.length, b.length);
        return IMPL.dotProduct(a, b);
    }

    public static float cosine(byte[] a, byte[] b) {
        checkDims(a.length, b.length);
        return IMPL.cosine(a, b);
    }

    public static int squareDistance(byte[] a, byte[] b) {
        checkDims(a.length, b.length);
        return IMPL.squareDistance(a, b);
    }

    public static float l2Distance(byte[] a, byte[] b) {
        return (float) Math.sqrt(squareDistance(a, b));
    }

    public static int l1Distance(byte[] a, byte[] b) {
        checkDims(a.length, b.length);
        return IMPL.l1Distance(a, b);
    }

    public static int int4DotProduct(byte[] a, byte[] b) {
        return dotProduct(a, b);
    }

    public static int int4DotProductPacked(byte[] unpacked, byte[] packed) {
        checkDims(unpacked.length, packed.length * 2);
        return IMPL.int4DotProductPacked(unpacked, packed);
    }

    public static int int4SquareDistancePacked(byte[] unpacked, byte[] packed) {
        checkDims(unpacked.length, packed.length * 2);
        return IMPL.int4SquareDistancePacked(unpacked, packed);
    }

    public static long xorBitCount(byte[] a, byte[] b) {
        checkDims(a.length, b.length);
        return IMPL.xorBitCount(a, b);
    }

    public static int hamming(byte[] a, byte[] b) {
        return (int) xorBitCount(a, b);
    }

    public static long int4BitDotProduct(byte[] query, byte[] binary) {
        checkDims(query.length, binary.length * 4);
        return IMPL.int4BitDotProduct(query, binary);
    }

    public static float magnitude(float[] v) {
        return (float) Math.sqrt(IMPL.dotProduct(v, v));
    }

    public static float magnitude(byte[] v) {
        return (float) Math.sqrt(IMPL.dotProduct(v, v));
    }

    public static float[] normalize(float[] v) {
        double sum = 0;
        for (float x : v) {
            sum += (double) x * x;
        }
        if (sum == 0) {
            throw new IllegalArgumentException("cannot normalize a zero-length vector");
        }
        double inv = 1.0 / Math.sqrt(sum);
        for (int i = 0; i < v.length; i++) {
            v[i] = (float) (v[i] * inv);
        }
        return v;
    }

    public static float[] normalizedCopy(float[] v) {
        return normalize(v.clone());
    }

    public static boolean isUnitVector(float[] v, float tolerance) {
        double sum = 0;
        for (float x : v) {
            sum += (double) x * x;
        }
        return Math.abs(sum - 1.0) <= tolerance;
    }

    public static float[] toFloats(byte[] v) {
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = v[i];
        }
        return out;
    }

    public static float scaleMaxInnerProductScore(float dot) {
        if (dot < 0) {
            return 1f / (1f - dot);
        }
        return dot + 1f;
    }
}
