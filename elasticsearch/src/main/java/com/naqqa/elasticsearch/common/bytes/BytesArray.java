package com.naqqa.elasticsearch.common.bytes;

import com.naqqa.elasticsearch.common.io.stream.ByteBufferStreamInput;
import com.naqqa.elasticsearch.common.io.stream.StreamInput;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class BytesArray implements BytesReference {

    private final byte[] bytes;
    private final int offset;
    private final int length;

    public BytesArray(byte[] bytes) {
        this(bytes, 0, bytes.length);
    }

    public BytesArray(byte[] bytes, int offset, int length) {
        this.bytes = bytes;
        this.offset = offset;
        this.length = length;
    }

    public BytesArray(String content) {
        this(content.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public int length() {
        return length;
    }

    @Override
    public byte get(int index) {
        return bytes[offset + index];
    }

    @Override
    public BytesReference slice(int from, int len) {
        return new BytesArray(bytes, offset + from, len);
    }

    @Override
    public byte[] toBytesArray() {
        if (offset == 0 && length == bytes.length) {
            return bytes;
        }
        return Arrays.copyOfRange(bytes, offset, offset + length);
    }

    @Override
    public BytesRef toBytesRef() {
        return new BytesRef(bytes, offset, length);
    }

    @Override
    public StreamInput streamInput() {
        return new ByteBufferStreamInput(bytes, offset, length);
    }

    @Override
    public String utf8ToString() {
        return new String(bytes, offset, length, StandardCharsets.UTF_8);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BytesReference other)) {
            return false;
        }
        return toBytesRef().equals(other.toBytesRef());
    }

    @Override
    public int hashCode() {
        return toBytesRef().hashCode();
    }

    @Override
    public String toString() {
        return utf8ToString();
    }
}
