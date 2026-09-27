package com.naqqa.elasticsearch.store;

import java.io.IOException;
import java.util.zip.CRC32C;
import java.util.zip.Checksum;

public final class BufferedChecksumIndexInput extends ChecksumIndexInput {

    private final IndexInput main;
    private final Checksum digest;
    private final byte[] scratch = new byte[8];
    private byte[] skipBuffer;

    public BufferedChecksumIndexInput(IndexInput main) {
        super("BufferedChecksumIndexInput(" + main + ")");
        this.main = main;
        this.digest = new CRC32C();
    }

    @Override
    public byte readByte() throws IOException {
        byte b = main.readByte();
        digest.update(b);
        return b;
    }

    @Override
    public void readBytes(byte[] b, int offset, int len) throws IOException {
        main.readBytes(b, offset, len);
        digest.update(b, offset, len);
    }

    @Override
    public short readShort() throws IOException {
        main.readBytes(scratch, 0, 2);
        digest.update(scratch, 0, 2);
        return BitIO.getShort(scratch, 0);
    }

    @Override
    public int readInt() throws IOException {
        main.readBytes(scratch, 0, 4);
        digest.update(scratch, 0, 4);
        return BitIO.getInt(scratch, 0);
    }

    @Override
    public long readLong() throws IOException {
        main.readBytes(scratch, 0, 8);
        digest.update(scratch, 0, 8);
        return BitIO.getLong(scratch, 0);
    }

    @Override
    protected void skipByReading(long numBytes) throws IOException {
        if (skipBuffer == null) {
            skipBuffer = new byte[8192];
        }
        long left = numBytes;
        while (left > 0) {
            int step = (int) Math.min(skipBuffer.length, left);
            readBytes(skipBuffer, 0, step);
            left -= step;
        }
    }

    @Override
    public void skipBytes(long numBytes) throws IOException {
        skipByReading(numBytes);
    }

    @Override
    public long getChecksum() {
        return digest.getValue();
    }

    @Override
    public void close() throws IOException {
        main.close();
    }

    @Override
    public long getFilePointer() {
        return main.getFilePointer();
    }

    @Override
    public long length() {
        return main.length();
    }

    @Override
    public IndexInput clone() {
        throw new IllegalStateException("cannot clone a checksum input");
    }

    @Override
    public IndexInput slice(String sliceDescription, long offset, long length) throws IOException {
        throw new IllegalStateException("cannot slice a checksum input");
    }
}
