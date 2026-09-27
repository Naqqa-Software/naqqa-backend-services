package com.naqqa.elasticsearch.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public abstract class DataInput implements Cloneable {

    private byte[] skipBuffer;

    public abstract byte readByte() throws IOException;

    public abstract void readBytes(byte[] b, int offset, int len) throws IOException;

    public void readBytes(byte[] b, int offset, int len, boolean useBuffer) throws IOException {
        readBytes(b, offset, len);
    }

    public short readShort() throws IOException {
        int b0 = readByte() & 0xFF;
        int b1 = readByte() & 0xFF;
        return (short) (b0 | (b1 << 8));
    }

    public int readInt() throws IOException {
        int b0 = readByte() & 0xFF;
        int b1 = readByte() & 0xFF;
        int b2 = readByte() & 0xFF;
        int b3 = readByte() & 0xFF;
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    public long readLong() throws IOException {
        long lo = readInt() & 0xFFFFFFFFL;
        long hi = readInt() & 0xFFFFFFFFL;
        return lo | (hi << 32);
    }

    public void readInts(int[] dst, int offset, int length) throws IOException {
        for (int i = 0; i < length; i++) {
            dst[offset + i] = readInt();
        }
    }

    public void readLongs(long[] dst, int offset, int length) throws IOException {
        for (int i = 0; i < length; i++) {
            dst[offset + i] = readLong();
        }
    }

    public void readFloats(float[] dst, int offset, int length) throws IOException {
        for (int i = 0; i < length; i++) {
            dst[offset + i] = Float.intBitsToFloat(readInt());
        }
    }

    public int readVInt() throws IOException {
        byte b = readByte();
        if (b >= 0) {
            return b;
        }
        int i = b & 0x7F;
        b = readByte();
        i |= (b & 0x7F) << 7;
        if (b >= 0) {
            return i;
        }
        b = readByte();
        i |= (b & 0x7F) << 14;
        if (b >= 0) {
            return i;
        }
        b = readByte();
        i |= (b & 0x7F) << 21;
        if (b >= 0) {
            return i;
        }
        b = readByte();
        if ((b & 0xF0) != 0) {
            throw new IOException("Invalid vInt detected (too many bits)");
        }
        i |= (b & 0x0F) << 28;
        return i;
    }

    public long readVLong() throws IOException {
        long result = 0;
        int shift = 0;
        for (int n = 0; n < 10; n++) {
            byte b = readByte();
            result |= (long) (b & 0x7F) << shift;
            if (b >= 0) {
                if (n == 9 && (b & 0xFE) != 0) {
                    throw new IOException("Invalid vLong detected (too many bits)");
                }
                return result;
            }
            shift += 7;
        }
        throw new IOException("Invalid vLong detected (more than 10 bytes)");
    }

    public int readZInt() throws IOException {
        int i = readVInt();
        return (i >>> 1) ^ -(i & 1);
    }

    public long readZLong() throws IOException {
        long l = readVLong();
        return (l >>> 1) ^ -(l & 1);
    }

    public String readString() throws IOException {
        int length = readVInt();
        if (length < 0) {
            throw new IOException("Invalid string length " + length);
        }
        byte[] bytes = new byte[length];
        readBytes(bytes, 0, length);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public Map<String, String> readMapOfStrings() throws IOException {
        int count = readVInt();
        if (count == 0) {
            return Collections.emptyMap();
        }
        Map<String, String> map = count > 10 ? new HashMap<>() : new java.util.TreeMap<>();
        for (int i = 0; i < count; i++) {
            String key = readString();
            String value = readString();
            map.put(key, value);
        }
        return Collections.unmodifiableMap(map);
    }

    public Set<String> readSetOfStrings() throws IOException {
        int count = readVInt();
        if (count == 0) {
            return Collections.emptySet();
        }
        Set<String> set = new HashSet<>();
        for (int i = 0; i < count; i++) {
            set.add(readString());
        }
        return Collections.unmodifiableSet(set);
    }

    public void skipBytes(long numBytes) throws IOException {
        if (numBytes < 0) {
            throw new IllegalArgumentException("numBytes must be >= 0, got " + numBytes);
        }
        if (skipBuffer == null) {
            skipBuffer = new byte[1024];
        }
        for (long skipped = 0; skipped < numBytes; ) {
            int step = (int) Math.min(skipBuffer.length, numBytes - skipped);
            readBytes(skipBuffer, 0, step);
            skipped += step;
        }
    }

    @Override
    public DataInput clone() {
        try {
            DataInput clone = (DataInput) super.clone();
            clone.skipBuffer = null;
            return clone;
        } catch (CloneNotSupportedException e) {
            throw new Error("This cannot happen: Failing to clone DataInput", e);
        }
    }
}
