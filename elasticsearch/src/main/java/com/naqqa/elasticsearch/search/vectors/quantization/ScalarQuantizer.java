package com.naqqa.elasticsearch.search.vectors.quantization;

import com.naqqa.elasticsearch.search.vectors.VectorUtil;

import java.util.Arrays;

public final class ScalarQuantizer {

    private final float lowerQuantile;
    private final float upperQuantile;
    private final int bits;
    private final float scale;

    public ScalarQuantizer(float lowerQuantile, float upperQuantile, int bits) {
        if (bits != 4 && bits != 7 && bits != 8) {
            throw new IllegalArgumentException("bits must be 4, 7 or 8, got " + bits);
        }
        if (upperQuantile <= lowerQuantile) {
            upperQuantile = lowerQuantile + 1e-4f;
        }
        this.lowerQuantile = lowerQuantile;
        this.upperQuantile = upperQuantile;
        this.bits = bits;
        int maxQuant = (1 << bits) - 1;
        this.scale = (upperQuantile - lowerQuantile) / maxQuant;
    }

    public static ScalarQuantizer fromVectors(float[][] vectors, float confidenceInterval, int bits) {
        int dim = vectors[0].length;
        int total = vectors.length * dim;
        float[] all = new float[total];
        int idx = 0;
        for (float[] v : vectors) {
            System.arraycopy(v, 0, all, idx, dim);
            idx += dim;
        }
        Arrays.sort(all);
        float discard = Math.max(0f, Math.min(1f, (1f - confidenceInterval) / 2f));
        int lowerIdx = (int) (discard * total);
        int upperIdx = total - 1 - lowerIdx;
        float lower = all[Math.max(0, Math.min(total - 1, lowerIdx))];
        float upper = all[Math.max(0, Math.min(total - 1, upperIdx))];
        return new ScalarQuantizer(lower, upper, bits);
    }

    public static float defaultConfidenceInterval(int dims) {
        return Math.max(0.9f, 1f - 1f / (dims + 1f));
    }

    public float lowerQuantile() {
        return lowerQuantile;
    }

    public float upperQuantile() {
        return upperQuantile;
    }

    public float scale() {
        return scale;
    }

    public int bits() {
        return bits;
    }

    private int maxQuant() {
        return (1 << bits) - 1;
    }

    public byte[] quantize(float[] vector) {
        int max = maxQuant();
        byte[] out = new byte[vector.length];
        for (int i = 0; i < vector.length; i++) {
            int q = Math.round((vector[i] - lowerQuantile) / scale);
            q = Math.max(0, Math.min(max, q));
            out[i] = (byte) q;
        }
        return out;
    }

    public float[] dequantize(byte[] q) {
        float[] out = new float[q.length];
        for (int i = 0; i < q.length; i++) {
            out[i] = lowerQuantile + scale * (q[i] & 0xFF);
        }
        return out;
    }

    public float correction(byte[] q) {
        long sum = 0;
        for (byte b : q) {
            sum += (b & 0xFF);
        }
        return lowerQuantile * scale * sum + 0.5f * q.length * lowerQuantile * lowerQuantile;
    }

    public float scoreDotProduct(byte[] q1, float correction1, byte[] q2, float correction2) {
        return correction1 + correction2 + scale * scale * VectorUtil.dotProduct(q1, q2);
    }

    public static byte[] packInt4(byte[] unpacked) {
        int half = (unpacked.length + 1) / 2;
        byte[] packed = new byte[half];
        for (int i = 0; i < half; i++) {
            int hi = unpacked[i] & 0x0F;
            int lo = (i + half < unpacked.length) ? (unpacked[i + half] & 0x0F) : 0;
            packed[i] = (byte) ((hi << 4) | lo);
        }
        return packed;
    }

    public static byte[] unpackInt4(byte[] packed, int dims) {
        int half = packed.length;
        byte[] out = new byte[dims];
        for (int i = 0; i < half; i++) {
            int p = packed[i] & 0xFF;
            out[i] = (byte) (p >>> 4);
            if (i + half < dims) {
                out[i + half] = (byte) (p & 0x0F);
            }
        }
        return out;
    }

    public float int4ScoreDotProductPacked(byte[] unpackedQuery, float queryCorrection, byte[] packedDoc, float docCorrection) {
        return queryCorrection + docCorrection + scale * scale * VectorUtil.int4DotProductPacked(unpackedQuery, packedDoc);
    }
}
