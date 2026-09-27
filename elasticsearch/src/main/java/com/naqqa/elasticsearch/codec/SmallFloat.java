package com.naqqa.elasticsearch.codec;

public final class SmallFloat {

    private SmallFloat() {
    }

    public static byte intToByte4(long i) {
        if (i < 0) {
            throw new IllegalArgumentException("i must be >= 0, got " + i);
        }
        if (i < 16) {
            return (byte) i;
        }
        int numBits = 64 - Long.numberOfLeadingZeros(i);
        int shift = numBits - 5;
        if (shift > 14) {
            shift = 14;
        }
        long top5 = (i >>> shift) & 0x1FL;
        long mantissa = top5 & 0x0FL;
        return (byte) (((shift + 1) << 4) | mantissa);
    }

    public static long byte4ToInt(byte b) {
        int v = b & 0xFF;
        if (v < 16) {
            return v;
        }
        int shiftPlus1 = v >>> 4;
        int shift = shiftPlus1 - 1;
        long mantissa = v & 0x0F;
        long top5 = mantissa | 0x10L;
        return top5 << shift;
    }

    public static float byteToFloat(byte b, int numMantissaBits, int zeroExponent) {
        if (b == 0) {
            return 0.0f;
        }
        int bits = (b & 0xff) << (24 - numMantissaBits);
        bits += (63 - zeroExponent) << 24;
        return Float.intBitsToFloat(bits);
    }

    public static byte floatToByte(float f, int numMantissaBits, int zeroExponent) {
        if (Float.isNaN(f) || f < 0.0f) {
            throw new IllegalArgumentException("f must be >= 0 and not NaN, got " + f);
        }
        if (f == 0.0f) {
            return 0;
        }
        if (f == Float.POSITIVE_INFINITY) {
            return -1;
        }
        int bits = Float.floatToIntBits(f);
        int smallfloat = bits >> (24 - numMantissaBits);
        if (smallfloat <= ((63 - zeroExponent) << numMantissaBits)) {
            return (byte) 1;
        }
        if (smallfloat >= ((63 - zeroExponent) << numMantissaBits) + 0x100) {
            return -1;
        }
        return (byte) (smallfloat - ((63 - zeroExponent) << numMantissaBits));
    }

    public static byte floatToByte315(float f) {
        return floatToByte(f, 3, 15);
    }

    public static float byte315ToFloat(byte b) {
        return byteToFloat(b, 3, 15);
    }

    public static byte floatToByte52(float f) {
        return floatToByte(f, 5, 2);
    }

    public static float byte52ToFloat(byte b) {
        return byteToFloat(b, 5, 2);
    }
}
