package com.naqqa.elasticsearch.codec;

import java.math.BigInteger;
import java.util.Arrays;

public final class NumericUtils {

    private NumericUtils() {
    }

    public static void longToSortableBytes(long value, byte[] result, int offset) {
        long v = value ^ 0x8000000000000000L;
        for (int i = 7; i >= 0; i--) {
            result[offset + i] = (byte) v;
            v >>>= 8;
        }
    }

    public static long sortableBytesToLong(byte[] bytes, int offset) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (bytes[offset + i] & 0xFFL);
        }
        return v ^ 0x8000000000000000L;
    }

    public static void intToSortableBytes(int value, byte[] result, int offset) {
        int v = value ^ 0x80000000;
        for (int i = 3; i >= 0; i--) {
            result[offset + i] = (byte) v;
            v >>>= 8;
        }
    }

    public static int sortableBytesToInt(byte[] bytes, int offset) {
        int v = 0;
        for (int i = 0; i < 4; i++) {
            v = (v << 8) | (bytes[offset + i] & 0xFF);
        }
        return v ^ 0x80000000;
    }

    public static long doubleToSortableLong(double value) {
        long bits = Double.doubleToLongBits(value);
        return bits ^ (bits >> 63 & 0x7FFFFFFFFFFFFFFFL);
    }

    public static double sortableLongToDouble(long encoded) {
        long bits = encoded ^ (encoded >> 63 & 0x7FFFFFFFFFFFFFFFL);
        return Double.longBitsToDouble(bits);
    }

    public static int floatToSortableInt(float value) {
        int bits = Float.floatToIntBits(value);
        return bits ^ (bits >> 31 & 0x7FFFFFFF);
    }

    public static float sortableIntToFloat(int encoded) {
        int bits = encoded ^ (encoded >> 31 & 0x7FFFFFFF);
        return Float.intBitsToFloat(bits);
    }

    public static short halfFloatToSortableShort(short halfFloatBits) {
        short bits = halfFloatBits;
        return (short) (bits ^ ((bits >> 15) & 0x7FFF));
    }

    public static short sortableShortToHalfFloat(short encoded) {
        return (short) (encoded ^ ((encoded >> 15) & 0x7FFF));
    }

    public static byte[] bigIntegerToSortableBytes(BigInteger value, int byteWidth) {
        byte[] result = new byte[byteWidth];
        bigIntegerToSortableBytes(value, result, 0, byteWidth);
        return result;
    }

    public static void bigIntegerToSortableBytes(BigInteger value, byte[] result, int offset, int byteWidth) {
        byte[] raw = value.toByteArray();
        if (raw.length > byteWidth) {
            throw new IllegalArgumentException("BigInteger too large for byte width " + byteWidth);
        }
        byte pad = value.signum() < 0 ? (byte) 0xFF : (byte) 0x00;
        Arrays.fill(result, offset, offset + byteWidth - raw.length, pad);
        System.arraycopy(raw, 0, result, offset + byteWidth - raw.length, raw.length);
        result[offset] ^= 0x80;
    }

    public static BigInteger sortableBytesToBigInteger(byte[] bytes, int offset, int byteWidth) {
        byte[] copy = Arrays.copyOfRange(bytes, offset, offset + byteWidth);
        copy[0] ^= 0x80;
        return new BigInteger(copy);
    }

    public static void ipToSortableBytes(byte[] ip16, byte[] result, int offset) {
        System.arraycopy(ip16, 0, result, offset, 16);
    }

    public static byte[] sortableBytesToIp(byte[] bytes, int offset) {
        return Arrays.copyOfRange(bytes, offset, offset + 16);
    }

    public static int compareUnsigned(byte[] a, int aOff, byte[] b, int bOff, int length) {
        for (int i = 0; i < length; i++) {
            int va = a[aOff + i] & 0xFF;
            int vb = b[bOff + i] & 0xFF;
            if (va != vb) {
                return va - vb;
            }
        }
        return 0;
    }
}
