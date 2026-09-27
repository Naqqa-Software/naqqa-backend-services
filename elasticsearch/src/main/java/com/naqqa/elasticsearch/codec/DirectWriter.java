package com.naqqa.elasticsearch.codec;

import com.naqqa.elasticsearch.store.DataOutput;

import java.io.IOException;

public final class DirectWriter {

    private final DataOutput out;
    private final int bitsPerValue;
    private int curByte;
    private int curBits;
    private long count;

    public DirectWriter(DataOutput out, int bitsPerValue) {
        if (bitsPerValue < 1 || bitsPerValue > 64) {
            throw new IllegalArgumentException("bitsPerValue must be in [1,64], got " + bitsPerValue);
        }
        this.out = out;
        this.bitsPerValue = bitsPerValue;
    }

    public void add(long value) throws IOException {
        writeBits(value, bitsPerValue);
        count++;
    }

    private void writeBits(long value, int bits) throws IOException {
        int remaining = bits;
        while (remaining > 0) {
            int room = 8 - curBits;
            int take = Math.min(room, remaining);
            long mask = take == 64 ? -1L : ((1L << take) - 1L);
            int shifted = (int) ((value >>> (remaining - take)) & mask);
            curByte |= shifted << (room - take);
            curBits += take;
            remaining -= take;
            if (curBits == 8) {
                out.writeByte((byte) curByte);
                curByte = 0;
                curBits = 0;
            }
        }
    }

    public void finish() throws IOException {
        if (curBits > 0) {
            out.writeByte((byte) curByte);
            curByte = 0;
            curBits = 0;
        }
    }

    public long count() {
        return count;
    }

    public int bitsPerValue() {
        return bitsPerValue;
    }

    public static int bitsRequired(long maxValue) {
        if (maxValue < 0) {
            throw new IllegalArgumentException("maxValue must be >= 0, got " + maxValue);
        }
        if (maxValue == 0) {
            return 1;
        }
        return 64 - Long.numberOfLeadingZeros(maxValue);
    }

    public static int unsignedBitsRequired(long minValue, long maxValue) {
        return bitsRequired(maxValue - minValue);
    }
}
