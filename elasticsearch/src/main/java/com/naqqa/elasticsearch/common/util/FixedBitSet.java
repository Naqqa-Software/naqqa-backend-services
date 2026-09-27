package com.naqqa.elasticsearch.common.util;

import java.util.Arrays;

public final class FixedBitSet {

    private final long[] bits;
    private final int numBits;

    public FixedBitSet(int numBits) {
        this.numBits = numBits;
        this.bits = new long[wordCount(numBits)];
    }

    private static int wordCount(int numBits) {
        return (numBits + 63) >>> 6;
    }

    public int length() {
        return numBits;
    }

    public boolean get(int index) {
        int wordNum = index >> 6;
        long bitmask = 1L << (index & 0x3F);
        return (bits[wordNum] & bitmask) != 0;
    }

    public void set(int index) {
        int wordNum = index >> 6;
        long bitmask = 1L << (index & 0x3F);
        bits[wordNum] |= bitmask;
    }

    public void clear(int index) {
        int wordNum = index >> 6;
        long bitmask = 1L << (index & 0x3F);
        bits[wordNum] &= ~bitmask;
    }

    public void flip(int index) {
        int wordNum = index >> 6;
        long bitmask = 1L << (index & 0x3F);
        bits[wordNum] ^= bitmask;
    }

    public int cardinality() {
        int sum = 0;
        for (long word : bits) {
            sum += Long.bitCount(word);
        }
        return sum;
    }

    public int nextSetBit(int index) {
        if (index >= numBits) {
            return -1;
        }
        int wordNum = index >> 6;
        long word = bits[wordNum] >>> (index & 0x3F) << (index & 0x3F);
        while (true) {
            if (word != 0) {
                int bit = wordNum * 64 + Long.numberOfTrailingZeros(word);
                return bit < numBits ? bit : -1;
            }
            wordNum++;
            if (wordNum >= bits.length) {
                return -1;
            }
            word = bits[wordNum];
        }
    }

    public void or(FixedBitSet other) {
        for (int i = 0; i < bits.length; i++) {
            bits[i] |= other.bits[i];
        }
    }

    public void and(FixedBitSet other) {
        for (int i = 0; i < bits.length; i++) {
            bits[i] &= other.bits[i];
        }
    }

    public void andNot(FixedBitSet other) {
        for (int i = 0; i < bits.length; i++) {
            bits[i] &= ~other.bits[i];
        }
    }

    public void clearAll() {
        Arrays.fill(bits, 0L);
    }

    public void setAll() {
        Arrays.fill(bits, -1L);
        int extra = bits.length * 64 - numBits;
        if (extra > 0 && bits.length > 0) {
            bits[bits.length - 1] >>>= extra;
        }
    }
}
