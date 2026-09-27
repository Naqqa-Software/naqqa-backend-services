package com.naqqa.elasticsearch.search.vectors;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

public final class ScalarVectorUtilSupport implements VectorUtilSupport {

    private static final VarHandle LONG_VIEW = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle INT_VIEW = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    public ScalarVectorUtilSupport() {
    }

    @Override
    public String name() {
        return "scalar";
    }

    @Override
    public float dotProduct(float[] a, float[] b) {
        float s0 = 0;
        float s1 = 0;
        float s2 = 0;
        float s3 = 0;
        int i = 0;
        int n = a.length;
        int bound = n & ~3;
        for (; i < bound; i += 4) {
            s0 += a[i] * b[i];
            s1 += a[i + 1] * b[i + 1];
            s2 += a[i + 2] * b[i + 2];
            s3 += a[i + 3] * b[i + 3];
        }
        float res = s0 + s1 + s2 + s3;
        for (; i < n; i++) {
            res += a[i] * b[i];
        }
        return res;
    }

    @Override
    public float cosine(float[] a, float[] b) {
        double dot = 0;
        double n1 = 0;
        double n2 = 0;
        for (int i = 0; i < a.length; i++) {
            float x = a[i];
            float y = b[i];
            dot += x * y;
            n1 += x * x;
            n2 += y * y;
        }
        return (float) (dot / Math.sqrt(n1 * n2));
    }

    @Override
    public float squareDistance(float[] a, float[] b) {
        float s0 = 0;
        float s1 = 0;
        int i = 0;
        int n = a.length;
        int bound = n & ~1;
        for (; i < bound; i += 2) {
            float d0 = a[i] - b[i];
            float d1 = a[i + 1] - b[i + 1];
            s0 += d0 * d0;
            s1 += d1 * d1;
        }
        float res = s0 + s1;
        for (; i < n; i++) {
            float d = a[i] - b[i];
            res += d * d;
        }
        return res;
    }

    @Override
    public float l1Distance(float[] a, float[] b) {
        float res = 0;
        for (int i = 0; i < a.length; i++) {
            res += Math.abs(a[i] - b[i]);
        }
        return res;
    }

    @Override
    public int dotProduct(byte[] a, byte[] b) {
        int res = 0;
        for (int i = 0; i < a.length; i++) {
            res += a[i] * b[i];
        }
        return res;
    }

    @Override
    public float cosine(byte[] a, byte[] b) {
        int dot = 0;
        int n1 = 0;
        int n2 = 0;
        for (int i = 0; i < a.length; i++) {
            int x = a[i];
            int y = b[i];
            dot += x * y;
            n1 += x * x;
            n2 += y * y;
        }
        return (float) (dot / Math.sqrt((double) n1 * (double) n2));
    }

    @Override
    public int squareDistance(byte[] a, byte[] b) {
        int res = 0;
        for (int i = 0; i < a.length; i++) {
            int d = a[i] - b[i];
            res += d * d;
        }
        return res;
    }

    @Override
    public int l1Distance(byte[] a, byte[] b) {
        int res = 0;
        for (int i = 0; i < a.length; i++) {
            res += Math.abs(a[i] - b[i]);
        }
        return res;
    }

    @Override
    public int int4DotProductPacked(byte[] unpacked, byte[] packed) {
        int half = packed.length;
        int res = 0;
        for (int i = 0; i < half; i++) {
            int p = packed[i] & 0xFF;
            res += (p >>> 4) * unpacked[i] + (p & 0x0F) * unpacked[half + i];
        }
        return res;
    }

    @Override
    public int int4SquareDistancePacked(byte[] unpacked, byte[] packed) {
        int half = packed.length;
        int res = 0;
        for (int i = 0; i < half; i++) {
            int p = packed[i] & 0xFF;
            int d1 = (p >>> 4) - unpacked[i];
            int d2 = (p & 0x0F) - unpacked[half + i];
            res += d1 * d1 + d2 * d2;
        }
        return res;
    }

    @Override
    public long xorBitCount(byte[] a, byte[] b) {
        return xorBitCount(a, 0, b, 0, a.length);
    }

    static long xorBitCount(byte[] a, int aOffset, byte[] b, int bOffset, int length) {
        long res = 0;
        int i = 0;
        for (; i + Long.BYTES <= length; i += Long.BYTES) {
            res += Long.bitCount((long) LONG_VIEW.get(a, aOffset + i) ^ (long) LONG_VIEW.get(b, bOffset + i));
        }
        for (; i + Integer.BYTES <= length; i += Integer.BYTES) {
            res += Integer.bitCount((int) INT_VIEW.get(a, aOffset + i) ^ (int) INT_VIEW.get(b, bOffset + i));
        }
        for (; i < length; i++) {
            res += Integer.bitCount((a[aOffset + i] ^ b[bOffset + i]) & 0xFF);
        }
        return res;
    }

    static long andBitCount(byte[] a, int aOffset, byte[] b, int length) {
        long res = 0;
        int i = 0;
        for (; i + Long.BYTES <= length; i += Long.BYTES) {
            res += Long.bitCount((long) LONG_VIEW.get(a, aOffset + i) & (long) LONG_VIEW.get(b, i));
        }
        for (; i + Integer.BYTES <= length; i += Integer.BYTES) {
            res += Integer.bitCount((int) INT_VIEW.get(a, aOffset + i) & (int) INT_VIEW.get(b, i));
        }
        for (; i < length; i++) {
            res += Integer.bitCount((a[aOffset + i] & b[i]) & 0xFF);
        }
        return res;
    }

    @Override
    public long int4BitDotProduct(byte[] query, byte[] binary) {
        int n = binary.length;
        long res = 0;
        for (int plane = 0; plane < 4; plane++) {
            res += andBitCount(query, plane * n, binary, n) << plane;
        }
        return res;
    }
}
