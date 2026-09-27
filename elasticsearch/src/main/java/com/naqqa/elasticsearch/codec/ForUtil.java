package com.naqqa.elasticsearch.codec;

import com.naqqa.elasticsearch.store.DataInput;
import com.naqqa.elasticsearch.store.DataOutput;

import java.io.IOException;

public final class ForUtil {

    public static final int BLOCK_SIZE = 128;

    private static final boolean VECTOR_SUPPORTED = detectVectorSupport();

    private ForUtil() {
    }

    private static boolean detectVectorSupport() {
        try {
            return VectorAdd.probe();
        } catch (Throwable t) {
            return false;
        }
    }

    public static void encodeBlock(int[] values, DataOutput out) throws IOException {
        if (values.length != BLOCK_SIZE) {
            throw new IllegalArgumentException("expected block of " + BLOCK_SIZE);
        }
        long min = Long.MAX_VALUE;
        for (int v : values) {
            if (v < 0) {
                throw new IllegalArgumentException("values must be non-negative");
            }
            if (v < min) {
                min = v;
            }
        }
        long[] delta = new long[BLOCK_SIZE];
        for (int i = 0; i < BLOCK_SIZE; i++) {
            delta[i] = values[i] - min;
        }
        int bits = chooseBits(delta);
        long mask = bits == 64 ? -1L : (1L << bits) - 1L;
        int exceptionCount = 0;
        for (long d : delta) {
            if (d > mask) {
                exceptionCount++;
            }
        }
        out.writeVLong(min);
        out.writeByte((byte) bits);
        out.writeVInt(exceptionCount);
        BitPackSequential.pack(out, delta, bits, mask);
        if (exceptionCount > 0) {
            for (int i = 0; i < BLOCK_SIZE; i++) {
                if (delta[i] > mask) {
                    out.writeVInt(i);
                    out.writeVLong(delta[i]);
                }
            }
        }
    }

    public static void decodeBlock(DataInput in, int[] dest) throws IOException {
        long min = in.readVLong();
        int bits = in.readByte() & 0xFF;
        int exceptionCount = in.readVInt();
        long[] raw = new long[BLOCK_SIZE];
        BitPackSequential.unpack(in, raw, bits);
        if (VECTOR_SUPPORTED && min >= Integer.MIN_VALUE && min <= Integer.MAX_VALUE) {
            VectorAdd.addMin(raw, (int) min, dest);
        } else {
            for (int i = 0; i < BLOCK_SIZE; i++) {
                dest[i] = (int) (min + raw[i]);
            }
        }
        for (int e = 0; e < exceptionCount; e++) {
            int idx = in.readVInt();
            long trueDelta = in.readVLong();
            dest[idx] = (int) (min + trueDelta);
        }
    }

    private static int chooseBits(long[] delta) {
        int maxAllowedExceptions = BLOCK_SIZE / 8;
        for (int bits = 0; bits <= 32; bits++) {
            long mask = bits == 64 ? -1L : (1L << bits) - 1L;
            int exceptions = 0;
            for (long d : delta) {
                if (d > mask) {
                    exceptions++;
                }
            }
            if (exceptions <= maxAllowedExceptions) {
                return bits;
            }
        }
        return 32;
    }

    private static final class BitPackSequential {
        static void pack(DataOutput out, long[] values, int bits, long mask) throws IOException {
            int curByte = 0;
            int curBits = 0;
            for (long value : values) {
                long v = value & mask;
                int remaining = bits;
                while (remaining > 0) {
                    int room = 8 - curBits;
                    int take = Math.min(room, remaining);
                    long m = take == 64 ? -1L : (1L << take) - 1L;
                    int shifted = (int) ((v >>> (remaining - take)) & m);
                    curByte |= shifted << (room - take);
                    curBits += take;
                    remaining -= take;
                    if (curBits == 8) {
                        out.writeByte((byte) curByte);
                        curByte = 0;
                        curBits = 0;
                    }
                }
            }
            if (curBits > 0) {
                out.writeByte((byte) curByte);
            }
        }

        static void unpack(DataInput in, long[] dest, int bits) throws IOException {
            if (bits == 0) {
                return;
            }
            int curByte = 0;
            int curBits = 0;
            for (int i = 0; i < dest.length; i++) {
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
                dest[i] = value;
            }
        }
    }

    private static final class VectorAdd {
        static boolean probe() {
            int[] a = {1, 2, 3, 4};
            int[] dest = new int[4];
            addMinScalarCheck(a, 1, dest);
            return jdk.incubator.vector.IntVector.SPECIES_PREFERRED.length() >= 1;
        }

        private static void addMinScalarCheck(int[] raw, int min, int[] dest) {
            for (int i = 0; i < raw.length; i++) {
                dest[i] = raw[i] + min;
            }
        }

        static void addMin(long[] raw, int min, int[] dest) {
            var species = jdk.incubator.vector.IntVector.SPECIES_PREFERRED;
            int len = raw.length;
            int[] rawInt = new int[len];
            for (int i = 0; i < len; i++) {
                rawInt[i] = (int) raw[i];
            }
            int i = 0;
            int upper = species.loopBound(len);
            for (; i < upper; i += species.length()) {
                var v = jdk.incubator.vector.IntVector.fromArray(species, rawInt, i);
                v.add(min).intoArray(dest, i);
            }
            for (; i < len; i++) {
                dest[i] = rawInt[i] + min;
            }
        }
    }
}
