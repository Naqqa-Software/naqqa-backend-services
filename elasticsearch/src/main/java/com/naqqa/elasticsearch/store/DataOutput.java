package com.naqqa.elasticsearch.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public abstract class DataOutput {

    private byte[] copyBuffer;

    public abstract void writeByte(byte b) throws IOException;

    public abstract void writeBytes(byte[] b, int offset, int length) throws IOException;

    public void writeBytes(byte[] b, int length) throws IOException {
        writeBytes(b, 0, length);
    }

    public void writeBytes(byte[] b) throws IOException {
        writeBytes(b, 0, b.length);
    }

    public void writeShort(short s) throws IOException {
        writeByte((byte) s);
        writeByte((byte) (s >> 8));
    }

    public void writeInt(int i) throws IOException {
        writeByte((byte) i);
        writeByte((byte) (i >> 8));
        writeByte((byte) (i >> 16));
        writeByte((byte) (i >> 24));
    }

    public void writeLong(long l) throws IOException {
        writeInt((int) l);
        writeInt((int) (l >>> 32));
    }

    public void writeInts(int[] values, int offset, int length) throws IOException {
        for (int i = 0; i < length; i++) {
            writeInt(values[offset + i]);
        }
    }

    public void writeLongs(long[] values, int offset, int length) throws IOException {
        for (int i = 0; i < length; i++) {
            writeLong(values[offset + i]);
        }
    }

    public void writeFloats(float[] values, int offset, int length) throws IOException {
        for (int i = 0; i < length; i++) {
            writeInt(Float.floatToIntBits(values[offset + i]));
        }
    }

    public final void writeVInt(int i) throws IOException {
        while ((i & ~0x7F) != 0) {
            writeByte((byte) ((i & 0x7F) | 0x80));
            i >>>= 7;
        }
        writeByte((byte) i);
    }

    public final void writeVLong(long i) throws IOException {
        while ((i & ~0x7FL) != 0L) {
            writeByte((byte) ((i & 0x7FL) | 0x80L));
            i >>>= 7;
        }
        writeByte((byte) i);
    }

    public final void writeZInt(int i) throws IOException {
        writeVInt((i >> 31) ^ (i << 1));
    }

    public final void writeZLong(long i) throws IOException {
        writeVLong((i >> 63) ^ (i << 1));
    }

    public void writeString(String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeVInt(bytes.length);
        writeBytes(bytes, 0, bytes.length);
    }

    public void writeMapOfStrings(Map<String, String> map) throws IOException {
        writeVInt(map.size());
        for (Map.Entry<String, String> entry : new TreeMap<>(map).entrySet()) {
            writeString(entry.getKey());
            writeString(entry.getValue());
        }
    }

    public void writeSetOfStrings(Set<String> set) throws IOException {
        writeVInt(set.size());
        for (String value : new TreeSet<>(set)) {
            writeString(value);
        }
    }

    public void copyBytes(DataInput input, long numBytes) throws IOException {
        if (numBytes < 0) {
            throw new IllegalArgumentException("numBytes must be >= 0, got " + numBytes);
        }
        if (copyBuffer == null) {
            copyBuffer = new byte[16384];
        }
        long left = numBytes;
        while (left > 0) {
            int step = (int) Math.min(copyBuffer.length, left);
            input.readBytes(copyBuffer, 0, step);
            writeBytes(copyBuffer, 0, step);
            left -= step;
        }
    }

    public static int vIntSize(int i) {
        int size = 1;
        while ((i & ~0x7F) != 0) {
            size++;
            i >>>= 7;
        }
        return size;
    }

    public static int vLongSize(long i) {
        int size = 1;
        while ((i & ~0x7FL) != 0L) {
            size++;
            i >>>= 7;
        }
        return size;
    }
}
