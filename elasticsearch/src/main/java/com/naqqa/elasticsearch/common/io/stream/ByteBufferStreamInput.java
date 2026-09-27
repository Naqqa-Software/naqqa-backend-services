package com.naqqa.elasticsearch.common.io.stream;

import java.nio.ByteBuffer;

public final class ByteBufferStreamInput extends StreamInput {

    private final ByteBuffer buffer;

    public ByteBufferStreamInput(ByteBuffer buffer) {
        this.buffer = buffer;
    }

    public ByteBufferStreamInput(byte[] bytes) {
        this(ByteBuffer.wrap(bytes));
    }

    public ByteBufferStreamInput(byte[] bytes, int offset, int length) {
        this(ByteBuffer.wrap(bytes, offset, length));
    }

    @Override
    public int read() {
        if (!buffer.hasRemaining()) {
            return -1;
        }
        return buffer.get() & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) {
        if (!buffer.hasRemaining()) {
            return -1;
        }
        int n = Math.min(len, buffer.remaining());
        buffer.get(b, off, n);
        return n;
    }

    public int available() {
        return buffer.remaining();
    }

    public int position() {
        return buffer.position();
    }
}
