package com.naqqa.elasticsearch.index.mapper;

final class NumericUtils {

    private NumericUtils() {
    }

    static byte[] longToSortableBytes(long value) {
        long sortable = value ^ 0x8000000000000000L;
        byte[] b = new byte[8];
        for (int i = 0; i < 8; i++) {
            b[i] = (byte) (sortable >>> (8 * (7 - i)));
        }
        return b;
    }

    static byte[] intToSortableBytes(int value) {
        int sortable = value ^ 0x80000000;
        byte[] b = new byte[4];
        for (int i = 0; i < 4; i++) {
            b[i] = (byte) (sortable >>> (8 * (3 - i)));
        }
        return b;
    }

    static long sortableDoubleBits(long bits) {
        return bits ^ ((bits >> 63) & 0x7fffffffffffffffL);
    }

    static int sortableFloatBits(int bits) {
        return bits ^ ((bits >> 31) & 0x7fffffff);
    }

    static long doubleToSortableLong(double value) {
        return sortableDoubleBits(Double.doubleToLongBits(value));
    }

    static int floatToSortableInt(float value) {
        return sortableFloatBits(Float.floatToIntBits(value));
    }

    static byte[] doubleToSortableBytes(double value) {
        return longToSortableBytes(doubleToSortableLong(value));
    }

    static byte[] floatToSortableBytes(float value) {
        return intToSortableBytes(floatToSortableInt(value));
    }

    static short floatToHalfFloat(float f) {
        int fBits = Float.floatToIntBits(f);
        int sign = (fBits >>> 16) & 0x8000;
        int exp = (fBits >>> 23) & 0xff;
        int mantissa = fBits & 0x7fffff;
        int newExp = exp - 127 + 15;
        if (exp == 0xff) {
            return (short) (sign | 0x7c00 | (mantissa != 0 ? 0x200 : 0));
        }
        if (newExp >= 0x1f) {
            return (short) (sign | 0x7c00);
        }
        if (newExp <= 0) {
            if (newExp < -10) {
                return (short) sign;
            }
            mantissa = mantissa | 0x800000;
            int shift = 14 - newExp;
            int rounded = mantissa >>> shift;
            return (short) (sign | rounded);
        }
        return (short) (sign | (newExp << 10) | (mantissa >>> 13));
    }

    static float halfFloatToFloat(short h) {
        int hInt = h & 0xffff;
        int sign = (hInt & 0x8000) << 16;
        int exp = (hInt >>> 10) & 0x1f;
        int mantissa = hInt & 0x3ff;
        if (exp == 0) {
            if (mantissa == 0) {
                return Float.intBitsToFloat(sign);
            }
            while ((mantissa & 0x400) == 0) {
                mantissa <<= 1;
                exp--;
            }
            exp++;
            mantissa &= 0x3ff;
        } else if (exp == 0x1f) {
            return Float.intBitsToFloat(sign | 0x7f800000 | (mantissa << 13));
        }
        exp = exp - 15 + 127;
        return Float.intBitsToFloat(sign | (exp << 23) | (mantissa << 13));
    }

    static float halfFloatRoundTrip(float f) {
        return halfFloatToFloat(floatToHalfFloat(f));
    }
}
