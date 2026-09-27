package com.naqqa.elasticsearch.common.bytes;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class BytesRef implements Comparable<BytesRef> {

    public static final byte[] EMPTY_BYTES = new byte[0];

    public byte[] bytes;
    public int offset;
    public int length;

    public BytesRef() {
        this(EMPTY_BYTES, 0, 0);
    }

    public BytesRef(byte[] bytes, int offset, int length) {
        this.bytes = bytes;
        this.offset = offset;
        this.length = length;
    }

    public BytesRef(byte[] bytes) {
        this(bytes, 0, bytes.length);
    }

    public BytesRef(CharSequence text) {
        this(text.toString().getBytes(StandardCharsets.UTF_8));
    }

    public String utf8ToString() {
        return new String(bytes, offset, length, StandardCharsets.UTF_8);
    }

    public byte[] toBytes() {
        return Arrays.copyOfRange(bytes, offset, offset + length);
    }

    public BytesRef clone() {
        return new BytesRef(toBytes());
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof BytesRef o)) {
            return false;
        }
        if (length != o.length) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            if (bytes[offset + i] != o.bytes[o.offset + i]) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = 0;
        int end = offset + length;
        for (int i = offset; i < end; i++) {
            result = 31 * result + bytes[i];
        }
        return result;
    }

    @Override
    public int compareTo(BytesRef other) {
        int minLen = Math.min(length, other.length);
        for (int i = 0; i < minLen; i++) {
            int a = bytes[offset + i] & 0xFF;
            int b = other.bytes[other.offset + i] & 0xFF;
            if (a != b) {
                return a - b;
            }
        }
        return length - other.length;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("[");
        int end = offset + length;
        for (int i = offset; i < end; i++) {
            if (i > offset) {
                sb.append(' ');
            }
            sb.append(Integer.toHexString(bytes[i] & 0xFF));
        }
        sb.append(']');
        return sb.toString();
    }
}
