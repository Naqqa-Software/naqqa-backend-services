package com.naqqa.elasticsearch.codec;

import com.naqqa.elasticsearch.store.RandomAccessInput;

import java.io.IOException;

public final class DirectReader {

    private final RandomAccessInput in;
    private final long startOffset;
    private final int bitsPerValue;

    public DirectReader(RandomAccessInput in, long startOffset, int bitsPerValue) {
        this.in = in;
        this.startOffset = startOffset;
        this.bitsPerValue = bitsPerValue;
    }

    public long get(long index) throws IOException {
        long bitStart = startOffset * 8 + index * bitsPerValue;
        long bytePos = bitStart >>> 3;
        int bitPosInByte = (int) (bitStart & 7);
        int remaining = bitsPerValue;
        long value = 0;
        while (remaining > 0) {
            int available = 8 - bitPosInByte;
            int take = Math.min(available, remaining);
            int byteVal = in.readByte(bytePos) & 0xFF;
            int shifted = (byteVal >>> (available - take)) & ((1 << take) - 1);
            value = (value << take) | shifted;
            remaining -= take;
            bitPosInByte += take;
            if (bitPosInByte == 8) {
                bitPosInByte = 0;
                bytePos++;
            }
        }
        return value;
    }

    public int bitsPerValue() {
        return bitsPerValue;
    }

    public static long byteSize(long count, int bitsPerValue) {
        return (count * bitsPerValue + 7) >>> 3;
    }
}
