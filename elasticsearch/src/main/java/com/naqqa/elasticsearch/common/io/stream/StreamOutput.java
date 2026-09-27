package com.naqqa.elasticsearch.common.io.stream;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public abstract class StreamOutput extends OutputStream {

    @Override
    public abstract void write(int b) throws IOException;

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        for (int i = 0; i < len; i++) {
            write(b[off + i] & 0xFF);
        }
    }

    public void writeByte(byte b) throws IOException {
        write(b);
    }

    public void writeBytes(byte[] b) throws IOException {
        write(b, 0, b.length);
    }

    public void writeBytes(byte[] b, int offset, int length) throws IOException {
        write(b, offset, length);
    }

    public void writeByteArray(byte[] b) throws IOException {
        writeVInt(b.length);
        writeBytes(b, 0, b.length);
    }

    public void writeShort(short v) throws IOException {
        write((v >>> 8) & 0xFF);
        write(v & 0xFF);
    }

    public void writeInt(int v) throws IOException {
        write((v >>> 24) & 0xFF);
        write((v >>> 16) & 0xFF);
        write((v >>> 8) & 0xFF);
        write(v & 0xFF);
    }

    public void writeLong(long v) throws IOException {
        write((int) ((v >>> 56) & 0xFF));
        write((int) ((v >>> 48) & 0xFF));
        write((int) ((v >>> 40) & 0xFF));
        write((int) ((v >>> 32) & 0xFF));
        write((int) ((v >>> 24) & 0xFF));
        write((int) ((v >>> 16) & 0xFF));
        write((int) ((v >>> 8) & 0xFF));
        write((int) (v & 0xFF));
    }

    public void writeVInt(int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        write(value);
    }

    public void writeVLong(long value) throws IOException {
        if (value < 0) {
            throw new IllegalStateException("Negative longs unsupported, use writeZLong for negatives, got " + value);
        }
        writeVLongNoCheck(value);
    }

    void writeVLongNoCheck(long value) throws IOException {
        while ((value & ~0x7FL) != 0) {
            write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        write((int) value);
    }

    public void writeZLong(long value) throws IOException {
        long zigZag = (value << 1) ^ (value >> 63);
        while ((zigZag & ~0x7FL) != 0) {
            write((int) ((zigZag & 0x7F) | 0x80));
            zigZag >>>= 7;
        }
        write((int) zigZag);
    }

    public void writeFloat(float v) throws IOException {
        writeInt(Float.floatToIntBits(v));
    }

    public void writeDouble(double v) throws IOException {
        writeLong(Double.doubleToLongBits(v));
    }

    public void writeBoolean(boolean b) throws IOException {
        write(b ? 1 : 0);
    }

    public void writeOptionalBoolean(Boolean b) throws IOException {
        if (b == null) {
            write(2);
        } else {
            writeBoolean(b);
        }
    }

    public void writeString(String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeVInt(bytes.length);
        writeBytes(bytes);
    }

    public void writeOptionalString(String s) throws IOException {
        writeBoolean(s != null);
        if (s != null) {
            writeString(s);
        }
    }

    public void writeOptionalInt(Integer v) throws IOException {
        writeBoolean(v != null);
        if (v != null) {
            writeVInt(v);
        }
    }

    public void writeOptionalLong(Long v) throws IOException {
        writeBoolean(v != null);
        if (v != null) {
            writeZLong(v);
        }
    }

    public void writeStringArray(String[] array) throws IOException {
        writeVInt(array.length);
        for (String s : array) {
            writeString(s);
        }
    }

    public void writeStringCollection(Collection<String> collection) throws IOException {
        writeVInt(collection.size());
        for (String s : collection) {
            writeString(s);
        }
    }

    public <T extends Writeable> void writeWriteable(T writeable) throws IOException {
        writeable.writeTo(this);
    }

    public <T extends Writeable> void writeOptionalWriteable(T writeable) throws IOException {
        if (writeable != null) {
            writeBoolean(true);
            writeable.writeTo(this);
        } else {
            writeBoolean(false);
        }
    }

    public <T> void writeCollection(Collection<T> collection, Writeable.Writer<T> writer) throws IOException {
        writeVInt(collection.size());
        for (T item : collection) {
            writer.write(this, item);
        }
    }

    public void writeCollection(Collection<? extends Writeable> collection) throws IOException {
        writeCollection(collection, (o, v) -> v.writeTo(o));
    }

    public <T> void writeOptionalCollection(Collection<T> collection, Writeable.Writer<T> writer) throws IOException {
        if (collection == null) {
            writeBoolean(false);
        } else {
            writeBoolean(true);
            writeCollection(collection, writer);
        }
    }

    @SuppressWarnings("unchecked")
    public void writeGenericValue(Object value) throws IOException {
        if (value == null) {
            write(0);
            return;
        }
        if (value instanceof String s) {
            write(1);
            writeString(s);
        } else if (value instanceof Integer i) {
            write(2);
            writeInt(i);
        } else if (value instanceof Long l) {
            write(3);
            writeLong(l);
        } else if (value instanceof Float f) {
            write(4);
            writeFloat(f);
        } else if (value instanceof Double d) {
            write(5);
            writeDouble(d);
        } else if (value instanceof Boolean b) {
            write(6);
            writeBoolean(b);
        } else if (value instanceof byte[] b) {
            write(7);
            writeByteArray(b);
        } else if (value instanceof Map<?, ?> m) {
            write(8);
            writeVInt(m.size());
            for (Map.Entry<?, ?> e : m.entrySet()) {
                writeGenericValue(e.getKey());
                writeGenericValue(e.getValue());
            }
        } else if (value instanceof List<?> l) {
            write(9);
            writeVInt(l.size());
            for (Object o : l) {
                writeGenericValue(o);
            }
        } else if (value instanceof Object[] arr) {
            write(10);
            writeVInt(arr.length);
            for (Object o : arr) {
                writeGenericValue(o);
            }
        } else if (value instanceof Short s) {
            write(11);
            writeShort(s);
        } else if (value instanceof BigInteger bi) {
            write(12);
            writeString(bi.toString());
        } else if (value instanceof BigDecimal bd) {
            write(13);
            writeString(bd.toString());
        } else if (value instanceof Byte b) {
            write(14);
            write(b);
        } else if (value instanceof Writeable w) {
            write(15);
            writeString(value.getClass().getName());
            w.writeTo(this);
        } else if (value instanceof Enum<?> e) {
            write(16);
            writeString(e.name());
        } else if (value instanceof Optional<?> opt) {
            write(17);
            writeBoolean(opt.isPresent());
            if (opt.isPresent()) {
                writeGenericValue(opt.get());
            }
        } else {
            throw new IllegalArgumentException("can not write type [" + value.getClass() + "]");
        }
    }

    @Override
    public void flush() throws IOException {
    }

    @Override
    public void close() throws IOException {
    }
}
