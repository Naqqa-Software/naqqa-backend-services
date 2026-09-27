package com.naqqa.elasticsearch.cluster.io;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GenericValueIO {

    private static final byte TYPE_NULL = 0;
    private static final byte TYPE_STRING = 1;
    private static final byte TYPE_INT = 2;
    private static final byte TYPE_LONG = 3;
    private static final byte TYPE_DOUBLE = 4;
    private static final byte TYPE_BOOLEAN = 5;
    private static final byte TYPE_MAP = 6;
    private static final byte TYPE_LIST = 7;

    private GenericValueIO() {
    }

    @SuppressWarnings("unchecked")
    public static void write(DataOutput out, Object value) throws IOException {
        if (value == null) {
            out.writeByte(TYPE_NULL);
        } else if (value instanceof String s) {
            out.writeByte(TYPE_STRING);
            StreamUtils.writeString(out, s);
        } else if (value instanceof Integer i) {
            out.writeByte(TYPE_INT);
            out.writeInt(i);
        } else if (value instanceof Long l) {
            out.writeByte(TYPE_LONG);
            out.writeLong(l);
        } else if (value instanceof Double d) {
            out.writeByte(TYPE_DOUBLE);
            out.writeDouble(d);
        } else if (value instanceof Boolean b) {
            out.writeByte(TYPE_BOOLEAN);
            out.writeBoolean(b);
        } else if (value instanceof Map<?, ?> map) {
            out.writeByte(TYPE_MAP);
            StreamUtils.writeVInt(out, map.size());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                StreamUtils.writeString(out, String.valueOf(entry.getKey()));
                write(out, entry.getValue());
            }
        } else if (value instanceof List<?> list) {
            out.writeByte(TYPE_LIST);
            StreamUtils.writeVInt(out, list.size());
            for (Object item : list) {
                write(out, item);
            }
        } else {
            out.writeByte(TYPE_STRING);
            StreamUtils.writeString(out, String.valueOf(value));
        }
    }

    public static Object read(DataInput in) throws IOException {
        byte type = in.readByte();
        return switch (type) {
            case TYPE_NULL -> null;
            case TYPE_STRING -> StreamUtils.readString(in);
            case TYPE_INT -> in.readInt();
            case TYPE_LONG -> in.readLong();
            case TYPE_DOUBLE -> in.readDouble();
            case TYPE_BOOLEAN -> in.readBoolean();
            case TYPE_MAP -> {
                int size = StreamUtils.readVInt(in);
                Map<String, Object> map = new LinkedHashMap<>(size);
                for (int i = 0; i < size; i++) {
                    map.put(StreamUtils.readString(in), read(in));
                }
                yield map;
            }
            case TYPE_LIST -> {
                int size = StreamUtils.readVInt(in);
                List<Object> list = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    list.add(read(in));
                }
                yield list;
            }
            default -> throw new IOException("unknown generic value type " + type);
        };
    }

    public static void writeMap(DataOutput out, Map<String, Object> map) throws IOException {
        write(out, map);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> readMap(DataInput in) throws IOException {
        return (Map<String, Object>) read(in);
    }
}
