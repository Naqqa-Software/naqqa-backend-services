package com.naqqa.elasticsearch.cluster.io;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class StreamUtils {

    private StreamUtils() {
    }

    public static void writeVInt(DataOutput out, int value) throws IOException {
        int v = value;
        while ((v & ~0x7F) != 0) {
            out.writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.writeByte(v);
    }

    public static int readVInt(DataInput in) throws IOException {
        byte b = in.readByte();
        int i = b & 0x7F;
        for (int shift = 7; (b & 0x80) != 0; shift += 7) {
            b = in.readByte();
            i |= (b & 0x7F) << shift;
        }
        return i;
    }

    public static void writeVLong(DataOutput out, long value) throws IOException {
        long v = value;
        while ((v & ~0x7FL) != 0) {
            out.writeByte((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        out.writeByte((int) v);
    }

    public static long readVLong(DataInput in) throws IOException {
        byte b = in.readByte();
        long i = b & 0x7F;
        for (int shift = 7; (b & 0x80) != 0; shift += 7) {
            b = in.readByte();
            i |= (long) (b & 0x7F) << shift;
        }
        return i;
    }

    public static void writeString(DataOutput out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVInt(out, bytes.length);
        out.write(bytes);
    }

    public static String readString(DataInput in) throws IOException {
        int length = readVInt(in);
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static void writeOptionalString(DataOutput out, String value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) {
            writeString(out, value);
        }
    }

    public static String readOptionalString(DataInput in) throws IOException {
        if (in.readBoolean()) {
            return readString(in);
        }
        return null;
    }

    public static void writeStringCollection(DataOutput out, Collection<String> values) throws IOException {
        writeVInt(out, values.size());
        for (String value : values) {
            writeString(out, value);
        }
    }

    public static List<String> readStringList(DataInput in) throws IOException {
        int size = readVInt(in);
        List<String> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(readString(in));
        }
        return list;
    }

    public static Set<String> readStringSet(DataInput in) throws IOException {
        int size = readVInt(in);
        Set<String> set = new LinkedHashSet<>(size);
        for (int i = 0; i < size; i++) {
            set.add(readString(in));
        }
        return set;
    }

    public static void writeStringMap(DataOutput out, Map<String, String> map) throws IOException {
        writeVInt(out, map.size());
        for (Map.Entry<String, String> entry : map.entrySet()) {
            writeString(out, entry.getKey());
            writeString(out, entry.getValue());
        }
    }

    public static Map<String, String> readStringMap(DataInput in) throws IOException {
        int size = readVInt(in);
        Map<String, String> map = new LinkedHashMap<>(size);
        for (int i = 0; i < size; i++) {
            map.put(readString(in), readString(in));
        }
        return map;
    }

    public static <T extends Writeable> void writeCollection(DataOutput out, Collection<T> values) throws IOException {
        writeVInt(out, values.size());
        for (T value : values) {
            value.writeTo(out);
        }
    }

    public static byte[] serialize(Writeable writeable) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream out = new java.io.DataOutputStream(bytes);
        writeable.writeTo(out);
        return bytes.toByteArray();
    }

    public static java.io.DataInputStream toInput(byte[] bytes) {
        return new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes));
    }
}
