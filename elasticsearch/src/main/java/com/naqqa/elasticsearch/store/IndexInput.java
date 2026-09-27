package com.naqqa.elasticsearch.store;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;

public abstract class IndexInput extends DataInput implements Closeable {

    private final String resourceDescription;

    protected IndexInput(String resourceDescription) {
        if (resourceDescription == null) {
            throw new IllegalArgumentException("resourceDescription must not be null");
        }
        this.resourceDescription = resourceDescription;
    }

    @Override
    public abstract void close() throws IOException;

    public abstract long getFilePointer();

    public abstract void seek(long pos) throws IOException;

    public abstract long length();

    public abstract IndexInput slice(String sliceDescription, long offset, long length) throws IOException;

    public RandomAccessInput randomAccessSlice(long offset, long length) throws IOException {
        IndexInput slice = slice("randomaccess", offset, length);
        if (slice instanceof RandomAccessInput rai) {
            return rai;
        }
        return new SeekingRandomAccessInput(slice);
    }

    @Override
    public void skipBytes(long numBytes) throws IOException {
        if (numBytes < 0) {
            throw new IllegalArgumentException("numBytes must be >= 0, got " + numBytes);
        }
        long target = getFilePointer() + numBytes;
        if (target > length()) {
            throw new EOFException("skip past EOF: " + this);
        }
        seek(target);
    }

    protected String getFullSliceDescription(String sliceDescription) {
        if (sliceDescription == null) {
            return toString();
        }
        return toString() + " [slice=" + sliceDescription + "]";
    }

    @Override
    public IndexInput clone() {
        return (IndexInput) super.clone();
    }

    @Override
    public String toString() {
        return resourceDescription;
    }

    private static final class SeekingRandomAccessInput implements RandomAccessInput {
        private final IndexInput in;

        SeekingRandomAccessInput(IndexInput in) {
            this.in = in;
        }

        @Override
        public long length() {
            return in.length();
        }

        @Override
        public byte readByte(long pos) throws IOException {
            in.seek(pos);
            return in.readByte();
        }

        @Override
        public void readBytes(long pos, byte[] bytes, int offset, int length) throws IOException {
            in.seek(pos);
            in.readBytes(bytes, offset, length);
        }

        @Override
        public short readShort(long pos) throws IOException {
            in.seek(pos);
            return in.readShort();
        }

        @Override
        public int readInt(long pos) throws IOException {
            in.seek(pos);
            return in.readInt();
        }

        @Override
        public long readLong(long pos) throws IOException {
            in.seek(pos);
            return in.readLong();
        }
    }
}
