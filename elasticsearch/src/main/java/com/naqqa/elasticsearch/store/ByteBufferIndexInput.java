package com.naqqa.elasticsearch.store;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class ByteBufferIndexInput extends IndexInput implements RandomAccessInput {

    static final class Guard {
        private final String description;
        private final Runnable onClose;
        volatile boolean invalidated;

        Guard(String description, Runnable onClose) {
            this.description = description;
            this.onClose = onClose;
        }

        void invalidate() {
            if (!invalidated) {
                invalidated = true;
                if (onClose != null) {
                    onClose.run();
                }
            }
        }
    }

    private final ByteBuffer[] buffers;
    private final int chunkPower;
    private final long chunkMask;
    private final long offset;
    private final long length;
    private final Guard guard;
    private final boolean isClone;

    private long pos;
    private ByteBuffer cur;
    private int curIdx;
    private int curChunk;

    public static ByteBufferIndexInput wrap(String description, ByteBuffer[] buffers, int chunkPower, long length, Runnable onClose) {
        ByteBuffer[] ordered = new ByteBuffer[buffers.length];
        for (int i = 0; i < buffers.length; i++) {
            ordered[i] = buffers[i].duplicate().order(ByteOrder.LITTLE_ENDIAN);
        }
        return new ByteBufferIndexInput(description, ordered, chunkPower, 0, length, new Guard(description, onClose), false);
    }

    public static ByteBufferIndexInput wrap(String description, byte[] bytes) {
        return wrap(description, new ByteBuffer[] {ByteBuffer.wrap(bytes)}, 31, bytes.length, null);
    }

    private ByteBufferIndexInput(String description, ByteBuffer[] buffers, int chunkPower, long offset, long length, Guard guard, boolean isClone) {
        super(description);
        this.buffers = buffers;
        this.chunkPower = chunkPower;
        this.chunkMask = (1L << chunkPower) - 1L;
        this.offset = offset;
        this.length = length;
        this.guard = guard;
        this.isClone = isClone;
        setPos(0);
    }

    private void ensureValid() {
        if (guard.invalidated) {
            throw new AlreadyClosedException("Already closed: " + guard.description);
        }
    }

    private void setPos(long relPos) {
        long abs = offset + relPos;
        int chunk = (int) (abs >>> chunkPower);
        if (chunk >= buffers.length) {
            chunk = buffers.length - 1;
        }
        curChunk = chunk;
        cur = buffers[chunk];
        curIdx = (int) (abs - ((long) chunk << chunkPower));
        pos = relPos;
    }

    private void nextChunk() throws EOFException {
        if (curChunk + 1 >= buffers.length) {
            throw new EOFException("read past EOF: " + this);
        }
        curChunk++;
        cur = buffers[curChunk];
        curIdx = 0;
    }

    @Override
    public byte readByte() throws IOException {
        ensureValid();
        if (pos >= length) {
            throw new EOFException("read past EOF: " + this);
        }
        if (curIdx >= cur.limit()) {
            nextChunk();
        }
        pos++;
        return cur.get(curIdx++);
    }

    @Override
    public void readBytes(byte[] b, int off, int len) throws IOException {
        ensureValid();
        if (len < 0 || pos + len > length) {
            throw new EOFException("read past EOF: " + this + " pos=" + pos + " len=" + len + " length=" + length);
        }
        int remaining = len;
        int o = off;
        while (remaining > 0) {
            int avail = cur.limit() - curIdx;
            if (avail <= 0) {
                nextChunk();
                continue;
            }
            int step = Math.min(avail, remaining);
            cur.get(curIdx, b, o, step);
            curIdx += step;
            o += step;
            remaining -= step;
        }
        pos += len;
    }

    @Override
    public short readShort() throws IOException {
        if (curIdx + 2 <= cur.limit() && pos + 2 <= length) {
            ensureValid();
            short v = cur.getShort(curIdx);
            curIdx += 2;
            pos += 2;
            return v;
        }
        return super.readShort();
    }

    @Override
    public int readInt() throws IOException {
        if (curIdx + 4 <= cur.limit() && pos + 4 <= length) {
            ensureValid();
            int v = cur.getInt(curIdx);
            curIdx += 4;
            pos += 4;
            return v;
        }
        return super.readInt();
    }

    @Override
    public long readLong() throws IOException {
        if (curIdx + 8 <= cur.limit() && pos + 8 <= length) {
            ensureValid();
            long v = cur.getLong(curIdx);
            curIdx += 8;
            pos += 8;
            return v;
        }
        return super.readLong();
    }

    @Override
    public void readInts(int[] dst, int off, int len) throws IOException {
        int bytes = len << 2;
        if (curIdx + bytes <= cur.limit() && pos + bytes <= length) {
            ensureValid();
            int idx = curIdx;
            for (int i = 0; i < len; i++) {
                dst[off + i] = cur.getInt(idx);
                idx += 4;
            }
            curIdx = idx;
            pos += bytes;
            return;
        }
        super.readInts(dst, off, len);
    }

    @Override
    public void readLongs(long[] dst, int off, int len) throws IOException {
        int bytes = len << 3;
        if (curIdx + bytes <= cur.limit() && pos + bytes <= length) {
            ensureValid();
            int idx = curIdx;
            for (int i = 0; i < len; i++) {
                dst[off + i] = cur.getLong(idx);
                idx += 8;
            }
            curIdx = idx;
            pos += bytes;
            return;
        }
        super.readLongs(dst, off, len);
    }

    @Override
    public void readFloats(float[] dst, int off, int len) throws IOException {
        int bytes = len << 2;
        if (curIdx + bytes <= cur.limit() && pos + bytes <= length) {
            ensureValid();
            int idx = curIdx;
            for (int i = 0; i < len; i++) {
                dst[off + i] = cur.getFloat(idx);
                idx += 4;
            }
            curIdx = idx;
            pos += bytes;
            return;
        }
        super.readFloats(dst, off, len);
    }

    @Override
    public long getFilePointer() {
        return pos;
    }

    @Override
    public void seek(long newPos) throws IOException {
        ensureValid();
        if (newPos < 0 || newPos > length) {
            throw new EOFException("seek past EOF: pos=" + newPos + " length=" + length + ": " + this);
        }
        setPos(newPos);
    }

    @Override
    public long length() {
        return length;
    }

    private int bufferIndexFor(long abs) {
        return (int) (abs >>> chunkPower);
    }

    private void checkRange(long p, int size) throws EOFException {
        if (p < 0 || p + size > length) {
            throw new EOFException("read past EOF: pos=" + p + " size=" + size + " length=" + length + ": " + this);
        }
    }

    @Override
    public byte readByte(long p) throws IOException {
        ensureValid();
        checkRange(p, 1);
        long abs = offset + p;
        return buffers[bufferIndexFor(abs)].get((int) (abs & chunkMask));
    }

    @Override
    public void readBytes(long p, byte[] bytes, int off, int len) throws IOException {
        ensureValid();
        checkRange(p, len);
        long abs = offset + p;
        int remaining = len;
        int o = off;
        while (remaining > 0) {
            int chunk = bufferIndexFor(abs);
            int idx = (int) (abs & chunkMask);
            ByteBuffer b = buffers[chunk];
            int step = Math.min(b.limit() - idx, remaining);
            b.get(idx, bytes, o, step);
            o += step;
            remaining -= step;
            abs += step;
        }
    }

    @Override
    public short readShort(long p) throws IOException {
        ensureValid();
        checkRange(p, 2);
        long abs = offset + p;
        int idx = (int) (abs & chunkMask);
        ByteBuffer b = buffers[bufferIndexFor(abs)];
        if (idx + 2 <= b.limit()) {
            return b.getShort(idx);
        }
        byte[] tmp = new byte[2];
        readBytes(p, tmp, 0, 2);
        return BitIO.getShort(tmp, 0);
    }

    @Override
    public int readInt(long p) throws IOException {
        ensureValid();
        checkRange(p, 4);
        long abs = offset + p;
        int idx = (int) (abs & chunkMask);
        ByteBuffer b = buffers[bufferIndexFor(abs)];
        if (idx + 4 <= b.limit()) {
            return b.getInt(idx);
        }
        byte[] tmp = new byte[4];
        readBytes(p, tmp, 0, 4);
        return BitIO.getInt(tmp, 0);
    }

    @Override
    public long readLong(long p) throws IOException {
        ensureValid();
        checkRange(p, 8);
        long abs = offset + p;
        int idx = (int) (abs & chunkMask);
        ByteBuffer b = buffers[bufferIndexFor(abs)];
        if (idx + 8 <= b.limit()) {
            return b.getLong(idx);
        }
        byte[] tmp = new byte[8];
        readBytes(p, tmp, 0, 8);
        return BitIO.getLong(tmp, 0);
    }

    @Override
    public ByteBufferIndexInput clone() {
        ensureValid();
        ByteBufferIndexInput clone = new ByteBufferIndexInput(toString(), buffers, chunkPower, offset, length, guard, true);
        clone.setPos(pos);
        return clone;
    }

    @Override
    public ByteBufferIndexInput slice(String sliceDescription, long sliceOffset, long sliceLength) throws IOException {
        ensureValid();
        if (sliceOffset < 0 || sliceLength < 0 || sliceOffset + sliceLength > length) {
            throw new IllegalArgumentException("slice() " + sliceDescription + " out of bounds: offset=" + sliceOffset
                + ",length=" + sliceLength + ",fileLength=" + length + ": " + this);
        }
        return new ByteBufferIndexInput(getFullSliceDescription(sliceDescription), buffers, chunkPower, offset + sliceOffset, sliceLength, guard, true);
    }

    @Override
    public RandomAccessInput randomAccessSlice(long sliceOffset, long sliceLength) throws IOException {
        return slice("randomaccess", sliceOffset, sliceLength);
    }

    @Override
    public void close() {
        if (!isClone) {
            guard.invalidate();
        }
    }
}
