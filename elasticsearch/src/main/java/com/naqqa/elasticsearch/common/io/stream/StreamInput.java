package com.naqqa.elasticsearch.common.io.stream;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public abstract class StreamInput extends InputStream {

    @Override
    public abstract int read() throws IOException;

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        for (int i = 0; i < len; i++) {
            int c = read();
            if (c < 0) {
                return i == 0 ? -1 : i;
            }
            b[off + i] = (byte) c;
        }
        return len;
    }

    public byte readByte() throws IOException {
        int b = read();
        if (b < 0) {
            throw new EOFException();
        }
        return (byte) b;
    }

    public void readBytes(byte[] b, int offset, int length) throws IOException {
        int read = 0;
        while (read < length) {
            int n = read(b, offset + read, length - read);
            if (n < 0) {
                throw new EOFException();
            }
            read += n;
        }
    }

    public byte[] readByteArray() throws IOException {
        int len = readVInt();
        byte[] b = new byte[len];
        readBytes(b, 0, len);
        return b;
    }

    public short readShort() throws IOException {
        return (short) (((readByte() & 0xFF) << 8) | (readByte() & 0xFF));
    }

    public int readInt() throws IOException {
        return ((readByte() & 0xFF) << 24) | ((readByte() & 0xFF) << 16) | ((readByte() & 0xFF) << 8) | (readByte() & 0xFF);
    }

    public long readLong() throws IOException {
        long high = readInt() & 0xFFFFFFFFL;
        long low = readInt() & 0xFFFFFFFFL;
        return (high << 32) | low;
    }

    public int readVInt() throws IOException {
        byte b = readByte();
        int i = b & 0x7F;
        for (int shift = 7; (b & 0x80) != 0; shift += 7) {
            b = readByte();
            i |= (b & 0x7F) << shift;
        }
        return i;
    }

    public long readVLong() throws IOException {
        byte b = readByte();
        long i = b & 0x7FL;
        for (int shift = 7; (b & 0x80) != 0; shift += 7) {
            b = readByte();
            i |= (b & 0x7FL) << shift;
        }
        return i;
    }

    public long readZLong() throws IOException {
        long value = 0L;
        int i = 0;
        long b;
        do {
            b = readByte();
            value |= (b & 0x7F) << i;
            i += 7;
        } while ((b & 0x80) != 0);
        return (value >>> 1) ^ -(value & 1);
    }

    public float readFloat() throws IOException {
        return Float.intBitsToFloat(readInt());
    }

    public double readDouble() throws IOException {
        return Double.longBitsToDouble(readLong());
    }

    public boolean readBoolean() throws IOException {
        return readByte() != 0;
    }

    public Boolean readOptionalBoolean() throws IOException {
        byte b = readByte();
        if (b == 2) {
            return null;
        }
        return b != 0;
    }

    public String readString() throws IOException {
        int len = readVInt();
        byte[] b = new byte[len];
        readBytes(b, 0, len);
        return new String(b, StandardCharsets.UTF_8);
    }

    public String readOptionalString() throws IOException {
        if (readBoolean()) {
            return readString();
        }
        return null;
    }

    public Integer readOptionalInt() throws IOException {
        if (readBoolean()) {
            return readVInt();
        }
        return null;
    }

    public Long readOptionalLong() throws IOException {
        if (readBoolean()) {
            return readZLong();
        }
        return null;
    }

    public String[] readStringArray() throws IOException {
        int size = readVInt();
        String[] arr = new String[size];
        for (int i = 0; i < size; i++) {
            arr[i] = readString();
        }
        return arr;
    }

    public List<String> readStringList() throws IOException {
        int size = readVInt();
        List<String> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(readString());
        }
        return list;
    }

    public <T> List<T> readList(Writeable.Reader<T> reader) throws IOException {
        int size = readVInt();
        List<T> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(reader.read(this));
        }
        return list;
    }

    public <T> List<T> readOptionalList(Writeable.Reader<T> reader) throws IOException {
        if (readBoolean()) {
            return readList(reader);
        }
        return null;
    }

    public <T extends Writeable> T readOptionalWriteable(Writeable.Reader<T> reader) throws IOException {
        if (readBoolean()) {
            return reader.read(this);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public Object readGenericValue() throws IOException {
        int type = read();
        switch (type) {
            case 0:
                return null;
            case 1:
                return readString();
            case 2:
                return readInt();
            case 3:
                return readLong();
            case 4:
                return readFloat();
            case 5:
                return readDouble();
            case 6:
                return readBoolean();
            case 7:
                return readByteArray();
            case 8: {
                int size = readVInt();
                Map<Object, Object> map = new LinkedHashMap<>();
                for (int i = 0; i < size; i++) {
                    map.put(readGenericValue(), readGenericValue());
                }
                return map;
            }
            case 9: {
                int size = readVInt();
                List<Object> list = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    list.add(readGenericValue());
                }
                return list;
            }
            case 10: {
                int size = readVInt();
                Object[] arr = new Object[size];
                for (int i = 0; i < size; i++) {
                    arr[i] = readGenericValue();
                }
                return arr;
            }
            case 11:
                return readShort();
            case 12:
                return new BigInteger(readString());
            case 13:
                return new BigDecimal(readString());
            case 14:
                return readByte();
            case 15:
                throw new IOException("cannot deserialize opaque Writeable of type [" + readString() + "] without a registry");
            case 16:
                return readString();
            case 17:
                return readBoolean() ? Optional.of(readGenericValue()) : Optional.empty();
            default:
                throw new IOException("Can't read generic value type [" + type + "]");
        }
    }

    @Override
    public void close() throws IOException {
    }
}
