package com.naqqa.elasticsearch.search.vectors.quantization;

import com.naqqa.elasticsearch.search.vectors.VectorUtil;

public final class BinaryQuantizer {

    private final float[] centroid;
    private final float centroidNormSquared;

    public record Quantized(byte[] bits, float centroidDot, float residualScale, int popcount) {
    }

    public BinaryQuantizer(float[] centroid) {
        this.centroid = centroid;
        this.centroidNormSquared = VectorUtil.dotProduct(centroid, centroid);
    }

    public float[] centroid() {
        return centroid;
    }

    public Quantized quantize(float[] vector) {
        int dims = vector.length;
        byte[] bits = new byte[(dims + 7) / 8];
        float centroidDot = 0f;
        double residualAbsSum = 0;
        for (int i = 0; i < dims; i++) {
            float residual = vector[i] - centroid[i];
            centroidDot += vector[i] * centroid[i];
            residualAbsSum += Math.abs(residual);
            if (residual > 0) {
                bits[i >> 3] |= (byte) (1 << (i & 7));
            }
        }
        float residualScale = (float) (residualAbsSum / dims);
        return new Quantized(bits, centroidDot, residualScale, popcount(bits));
    }

    public static int popcount(byte[] bits) {
        int c = 0;
        for (byte b : bits) {
            c += Integer.bitCount(b & 0xFF);
        }
        return c;
    }

    public static int andPopcount(byte[] a, byte[] b) {
        int c = 0;
        for (int i = 0; i < a.length; i++) {
            c += Integer.bitCount((a[i] & 0xFF) & (b[i] & 0xFF));
        }
        return c;
    }

    public float approximateDotProduct(Quantized a, Quantized b, int dims) {
        int andCount = andPopcount(a.bits(), b.bits());
        double signDot = 4.0 * andCount - 2.0 * a.popcount() - 2.0 * b.popcount() + dims;
        return a.centroidDot() + b.centroidDot() - centroidNormSquared + (float) (a.residualScale() * b.residualScale() * signDot);
    }

    public byte[] quantizeQueryInt4(float[] vector, float lowerQuantile, float upperQuantile) {
        ScalarQuantizer sq = new ScalarQuantizer(lowerQuantile, upperQuantile, 4);
        return sq.quantize(vector);
    }

    public static byte[] buildBitPlanes(byte[] int4Query) {
        int dims = int4Query.length;
        int n = (dims + 7) / 8;
        byte[] planes = new byte[4 * n];
        for (int i = 0; i < dims; i++) {
            int v = int4Query[i] & 0x0F;
            int byteIndex = i >> 3;
            int bitMask = 1 << (i & 7);
            for (int plane = 0; plane < 4; plane++) {
                if (((v >>> plane) & 1) != 0) {
                    planes[plane * n + byteIndex] |= (byte) bitMask;
                }
            }
        }
        return planes;
    }

    public static long asymmetricScore(byte[] int4QueryPlanes, byte[] docBits) {
        return VectorUtil.int4BitDotProduct(int4QueryPlanes, docBits);
    }
}
