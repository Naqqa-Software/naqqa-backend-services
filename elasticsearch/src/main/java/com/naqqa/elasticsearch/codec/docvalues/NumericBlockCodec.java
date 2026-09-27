package com.naqqa.elasticsearch.codec.docvalues;

import com.naqqa.elasticsearch.codec.DirectWriter;
import com.naqqa.elasticsearch.store.DataInput;
import com.naqqa.elasticsearch.store.DataOutput;

import java.io.IOException;
import java.util.Arrays;
import java.util.TreeSet;

public final class NumericBlockCodec {

    public static final byte MODE_EMPTY = 0;
    public static final byte MODE_CONSTANT = 1;
    public static final byte MODE_TABLE = 2;
    public static final byte MODE_DELTA_GCD = 3;

    private NumericBlockCodec() {
    }

    public static void writePacked(DataOutput out, long[] values) throws IOException {
        if (values.length == 0) {
            out.writeByte(MODE_EMPTY);
            return;
        }
        long min = values[0];
        long max = values[0];
        for (long v : values) {
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        if (min == max) {
            out.writeByte(MODE_CONSTANT);
            out.writeVLong(min);
            return;
        }
        TreeSet<Long> distinctSet = new TreeSet<>();
        for (long v : values) {
            distinctSet.add(v);
            if (distinctSet.size() > 256) {
                break;
            }
        }
        if (distinctSet.size() <= 256) {
            writeTable(out, values, distinctSet);
        } else {
            writeDeltaGcd(out, values, min, max);
        }
    }

    private static void writeTable(DataOutput out, long[] values, TreeSet<Long> distinctSet) throws IOException {
        Long[] table = distinctSet.toArray(new Long[0]);
        out.writeByte(MODE_TABLE);
        out.writeVInt(table.length);
        for (Long v : table) {
            out.writeZLong(v);
        }
        int bits = DirectWriter.bitsRequired(table.length - 1);
        out.writeByte((byte) bits);
        DirectWriter writer = new DirectWriter(out, bits);
        for (long v : values) {
            int idx = Arrays.binarySearch(table, v);
            writer.add(idx);
        }
        writer.finish();
    }

    private static long gcd(long a, long b) {
        while (b != 0) {
            long t = b;
            b = a % b;
            a = t;
        }
        return a;
    }

    private static void writeDeltaGcd(DataOutput out, long[] values, long min, long max) throws IOException {
        long g = 0;
        for (long v : values) {
            g = gcd(g, v - min);
            if (g == 1) {
                break;
            }
        }
        if (g == 0) {
            g = 1;
        }
        int bits = DirectWriter.bitsRequired((max - min) / g);
        out.writeByte(MODE_DELTA_GCD);
        out.writeZLong(min);
        out.writeVLong(g);
        out.writeByte((byte) bits);
        DirectWriter writer = new DirectWriter(out, bits);
        for (long v : values) {
            writer.add((v - min) / g);
        }
        writer.finish();
    }

    public static long[] readPacked(DataInput in, int count) throws IOException {
        byte mode = in.readByte();
        if (mode == MODE_EMPTY) {
            return new long[0];
        }
        if (mode == MODE_CONSTANT) {
            long v = in.readVLong();
            long[] result = new long[count];
            Arrays.fill(result, v);
            return result;
        }
        if (mode == MODE_TABLE) {
            int tableSize = in.readVInt();
            long[] table = new long[tableSize];
            for (int i = 0; i < tableSize; i++) {
                table[i] = in.readZLong();
            }
            int bits = in.readByte() & 0xFF;
            long[] result = new long[count];
            SequentialBitReader reader = new SequentialBitReader(in, bits);
            for (int i = 0; i < count; i++) {
                result[i] = table[(int) reader.next()];
            }
            return result;
        }
        if (mode == MODE_DELTA_GCD) {
            long min = in.readZLong();
            long g = in.readVLong();
            int bits = in.readByte() & 0xFF;
            long[] result = new long[count];
            SequentialBitReader reader = new SequentialBitReader(in, bits);
            for (int i = 0; i < count; i++) {
                result[i] = min + reader.next() * g;
            }
            return result;
        }
        throw new IOException("unknown numeric block mode " + mode);
    }

    private static final class SequentialBitReader {
        private final DataInput in;
        private final int bits;
        private int curByte;
        private int curBits;

        SequentialBitReader(DataInput in, int bits) {
            this.in = in;
            this.bits = bits;
        }

        long next() throws IOException {
            if (bits == 0) {
                return 0;
            }
            long value = 0;
            int remaining = bits;
            while (remaining > 0) {
                if (curBits == 0) {
                    curByte = in.readByte() & 0xFF;
                    curBits = 8;
                }
                int take = Math.min(curBits, remaining);
                int shifted = (curByte >>> (curBits - take)) & ((1 << take) - 1);
                value = (value << take) | shifted;
                curBits -= take;
                remaining -= take;
            }
            return value;
        }
    }
}
