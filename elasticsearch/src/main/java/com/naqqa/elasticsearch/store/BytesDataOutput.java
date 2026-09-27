package com.naqqa.elasticsearch.store;

import java.io.IOException;
import java.util.Arrays;

public final class BytesDataOutput extends DataOutput {

    private byte[] bytes;
    private int size;

    public BytesDataOutput() {
        this(256);
    }

    public BytesDataOutput(int initialCapacity) {
        this.bytes = new byte[Math.max(16, initialCapacity)];
    }

    private void ensureCapacity(int extra) {
        int needed = size + extra;
        if (needed < 0) {
            throw new IllegalStateException("buffer too large");
        }
        if (needed > bytes.length) {
            int newLength = Math.max(needed, bytes.length + (bytes.length >> 1));
            if (newLength < 0) {
                newLength = Integer.MAX_VALUE - 8;
            }
            bytes = Arrays.copyOf(bytes, newLength);
        }
    }

    @Override
    public void writeByte(byte b) {
        if (size == bytes.length) {
            ensureCapacity(1);
        }
        bytes[size++] = b;
    }

    @Override
    public void writeBytes(byte[] b, int offset, int length) {
        ensureCapacity(length);
        System.arraycopy(b, offset, bytes, size, length);
        size += length;
    }

    @Override
    public void writeShort(short s) {
        ensureCapacity(2);
        BitIO.putShort(bytes, size, s);
        size += 2;
    }

    @Override
    public void writeInt(int i) {
        ensureCapacity(4);
        BitIO.putInt(bytes, size, i);
        size += 4;
    }

    @Override
    public void writeLong(long l) {
        ensureCapacity(8);
        BitIO.putLong(bytes, size, l);
        size += 8;
    }

    public int size() {
        return size;
    }

    public void reset() {
        size = 0;
    }

    public void truncate(int newSize) {
        if (newSize > size || newSize < 0) {
            throw new IllegalArgumentException("invalid size " + newSize);
        }
        size = newSize;
    }

    public byte[] getBytes() {
        return bytes;
    }

    public byte[] toArrayCopy() {
        return Arrays.copyOf(bytes, size);
    }

    public void writeTo(DataOutput out) throws IOException {
        out.writeBytes(bytes, 0, size);
    }

    public ByteArrayDataInput toDataInput() {
        return new ByteArrayDataInput(bytes, 0, size);
    }
}
