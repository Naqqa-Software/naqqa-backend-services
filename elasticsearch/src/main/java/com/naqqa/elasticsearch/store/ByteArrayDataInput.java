package com.naqqa.elasticsearch.store;

import java.io.EOFException;
import java.io.IOException;

public final class ByteArrayDataInput extends DataInput {

    private byte[] bytes;
    private int pos;
    private int limit;

    public ByteArrayDataInput() {
        this(new byte[0]);
    }

    public ByteArrayDataInput(byte[] bytes) {
        reset(bytes, 0, bytes.length);
    }

    public ByteArrayDataInput(byte[] bytes, int offset, int length) {
        reset(bytes, offset, length);
    }

    public void reset(byte[] bytes) {
        reset(bytes, 0, bytes.length);
    }

    public void reset(byte[] bytes, int offset, int length) {
        this.bytes = bytes;
        this.pos = offset;
        this.limit = offset + length;
    }

    public int getPosition() {
        return pos;
    }

    public void setPosition(int pos) {
        this.pos = pos;
    }

    public int length() {
        return limit;
    }

    public boolean eof() {
        return pos >= limit;
    }

    public byte[] bytes() {
        return bytes;
    }

    @Override
    public void skipBytes(long count) {
        pos += (int) count;
    }

    @Override
    public byte readByte() throws IOException {
        if (pos >= limit) {
            throw new EOFException("read past EOF");
        }
        return bytes[pos++];
    }

    @Override
    public void readBytes(byte[] b, int offset, int len) throws IOException {
        if (pos + len > limit) {
            throw new EOFException("read past EOF");
        }
        System.arraycopy(bytes, pos, b, offset, len);
        pos += len;
    }

    @Override
    public short readShort() throws IOException {
        if (pos + 2 > limit) {
            throw new EOFException("read past EOF");
        }
        short v = (short) ((bytes[pos] & 0xFF) | ((bytes[pos + 1] & 0xFF) << 8));
        pos += 2;
        return v;
    }

    @Override
    public int readInt() throws IOException {
        if (pos + 4 > limit) {
            throw new EOFException("read past EOF");
        }
        int v = BitIO.getInt(bytes, pos);
        pos += 4;
        return v;
    }

    @Override
    public long readLong() throws IOException {
        if (pos + 8 > limit) {
            throw new EOFException("read past EOF");
        }
        long v = BitIO.getLong(bytes, pos);
        pos += 8;
        return v;
    }

    @Override
    public int readVInt() throws IOException {
        if (limit - pos >= 5) {
            byte b = bytes[pos++];
            if (b >= 0) {
                return b;
            }
            int i = b & 0x7F;
            b = bytes[pos++];
            i |= (b & 0x7F) << 7;
            if (b >= 0) {
                return i;
            }
            b = bytes[pos++];
            i |= (b & 0x7F) << 14;
            if (b >= 0) {
                return i;
            }
            b = bytes[pos++];
            i |= (b & 0x7F) << 21;
            if (b >= 0) {
                return i;
            }
            b = bytes[pos++];
            if ((b & 0xF0) != 0) {
                throw new IOException("Invalid vInt detected (too many bits)");
            }
            return i | ((b & 0x0F) << 28);
        }
        return super.readVInt();
    }
}
