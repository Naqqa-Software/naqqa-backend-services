package com.naqqa.elasticsearch.common.io.stream;

import com.naqqa.elasticsearch.common.bytes.BytesArray;
import com.naqqa.elasticsearch.common.bytes.BytesReference;

import java.util.Arrays;

public final class BytesStreamOutput extends StreamOutput {

    private byte[] bytes;
    private int count;

    public BytesStreamOutput() {
        this(32);
    }

    public BytesStreamOutput(int expectedSize) {
        this.bytes = new byte[Math.max(expectedSize, 8)];
    }

    private void ensureCapacity(int minCapacity) {
        if (minCapacity - bytes.length > 0) {
            int newCapacity = Math.max(bytes.length * 2, minCapacity);
            bytes = Arrays.copyOf(bytes, newCapacity);
        }
    }

    @Override
    public void write(int b) {
        ensureCapacity(count + 1);
        bytes[count++] = (byte) b;
    }

    @Override
    public void write(byte[] b, int off, int len) {
        ensureCapacity(count + len);
        System.arraycopy(b, off, bytes, count, len);
        count += len;
    }

    public int size() {
        return count;
    }

    public void reset() {
        count = 0;
    }

    public byte[] toByteArray() {
        return Arrays.copyOf(bytes, count);
    }

    public BytesReference bytes() {
        return new BytesArray(bytes, 0, count);
    }
}
