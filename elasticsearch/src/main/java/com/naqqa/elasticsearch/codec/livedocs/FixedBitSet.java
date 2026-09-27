package com.naqqa.elasticsearch.codec.livedocs;

public final class FixedBitSet {

    private final long[] bits;
    private final int numBits;

    public FixedBitSet(int numBits) {
        this.numBits = numBits;
        this.bits = new long[(numBits + 63) >>> 6];
    }

    public FixedBitSet(long[] bits, int numBits) {
        this.bits = bits;
        this.numBits = numBits;
    }

    public int length() {
        return numBits;
    }

    public boolean get(int index) {
        return (bits[index >>> 6] & (1L << (index & 63))) != 0;
    }

    public void set(int index) {
        bits[index >>> 6] |= (1L << (index & 63));
    }

    public void clear(int index) {
        bits[index >>> 6] &= ~(1L << (index & 63));
    }

    public long[] words() {
        return bits;
    }

    public int cardinality() {
        int count = 0;
        for (long w : bits) {
            count += Long.bitCount(w);
        }
        return count;
    }

    public static FixedBitSet allSet(int numBits) {
        FixedBitSet result = new FixedBitSet(numBits);
        for (int i = 0; i < numBits; i++) {
            result.set(i);
        }
        return result;
    }
}
