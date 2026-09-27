package com.naqqa.elasticsearch.search.aggs.support.sketch;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;

public final class HyperLogLogPlusPlus {

    public static final int MIN_PRECISION = 4;
    public static final int MAX_PRECISION = 18;
    public static final int DEFAULT_PRECISION = 14;
    public static final double MAX_LOAD_FACTOR = 0.75;

    public static final byte LINEAR_COUNTING = 0;
    public static final byte HYPERLOGLOG = 1;

    private static final int P2 = 25;
    private static final int SERIAL_VERSION = 1;
    private static final int INITIAL_SET_CAPACITY = 16;
    private static final double ALPHA_INF = 1.0 / (2.0 * Math.log(2.0));

    private static final long[] THRESHOLDS = {
        10, 20, 40, 80, 220, 400, 900, 1800, 3100, 6500, 11500, 20000, 50000, 120000, 350000
    };

    private final int precision;
    private final int m;
    private final int maxSetCapacity;
    private final int linearCountingThreshold;

    private int[][] hashSets;
    private int[] setSizes;
    private byte[][] registers;

    public HyperLogLogPlusPlus(int precision) {
        this(precision, 1);
    }

    public HyperLogLogPlusPlus(int precision, long initialBucketCount) {
        checkPrecision(precision);
        if (initialBucketCount < 0 || initialBucketCount > Integer.MAX_VALUE - 8) {
            throw new IllegalArgumentException("invalid initial bucket count: " + initialBucketCount);
        }
        this.precision = precision;
        this.m = 1 << precision;
        this.maxSetCapacity = Math.max(4, m / 4);
        this.linearCountingThreshold = (int) (maxSetCapacity * MAX_LOAD_FACTOR);
        int n = (int) Math.max(1, initialBucketCount);
        this.hashSets = new int[n][];
        this.setSizes = new int[n];
        this.registers = new byte[n][];
    }

    public static int precisionFromThreshold(long count) {
        if (count < 0) {
            throw new IllegalArgumentException("precision threshold must be >= 0, got " + count);
        }
        long hashTableEntries = (long) Math.ceil(count / MAX_LOAD_FACTOR);
        long bytes = hashTableEntries * Integer.BYTES;
        int precision = bytes <= 0 ? 1 : 64 - Long.numberOfLeadingZeros(bytes);
        precision = Math.max(precision, MIN_PRECISION);
        precision = Math.min(precision, MAX_PRECISION);
        return precision;
    }

    public static long thresholdFromPrecision(int precision) {
        checkPrecision(precision);
        return 1L << (precision - 1);
    }

    public static long linearCountingCutoff(int precision) {
        checkPrecision(precision);
        return THRESHOLDS[precision - MIN_PRECISION];
    }

    public static double standardError(int precision) {
        checkPrecision(precision);
        return 1.04 / Math.sqrt(1 << precision);
    }

    private static void checkPrecision(int precision) {
        if (precision < MIN_PRECISION || precision > MAX_PRECISION) {
            throw new IllegalArgumentException("precision must be in [" + MIN_PRECISION + ", " + MAX_PRECISION + "], got " + precision);
        }
    }

    public int precision() {
        return precision;
    }

    public int registerCount() {
        return m;
    }

    public long maxOrd() {
        return setSizes.length;
    }

    public byte algorithm(long bucket) {
        int b = checkBucket(bucket);
        if (b < registers.length && registers[b] != null) {
            return HYPERLOGLOG;
        }
        return LINEAR_COUNTING;
    }

    public boolean isLinearCounting(long bucket) {
        return algorithm(bucket) == LINEAR_COUNTING;
    }

    private static int checkBucket(long bucket) {
        if (bucket < 0 || bucket > Integer.MAX_VALUE - 8) {
            throw new IllegalArgumentException("invalid bucket ordinal: " + bucket);
        }
        return (int) bucket;
    }

    private void ensureBucket(int b) {
        if (b >= setSizes.length) {
            int newSize = Math.max(b + 1, setSizes.length + (setSizes.length >> 1) + 1);
            hashSets = Arrays.copyOf(hashSets, newSize);
            setSizes = Arrays.copyOf(setSizes, newSize);
            registers = Arrays.copyOf(registers, newSize);
        }
    }

    public void collect(long bucket, long hash) {
        int b = checkBucket(bucket);
        ensureBucket(b);
        byte[] regs = registers[b];
        if (regs != null) {
            collectHll(regs, hash);
        } else {
            collectEncoded(b, encodeHash(hash, precision));
        }
    }

    public void collectLong(long bucket, long value) {
        collect(bucket, MurmurHash3.hash64(value));
    }

    public void collectDouble(long bucket, double value) {
        collect(bucket, MurmurHash3.hash64(value));
    }

    public void collectBytes(long bucket, byte[] value) {
        collect(bucket, MurmurHash3.hash64(value));
    }

    public void collectString(long bucket, String value) {
        collect(bucket, MurmurHash3.hash64(value));
    }

    private void collectHll(byte[] regs, long hash) {
        int index = (int) (hash >>> (64 - precision));
        int runLen = Math.min(Long.numberOfLeadingZeros(hash << precision), 64 - precision) + 1;
        if (regs[index] < runLen) {
            regs[index] = (byte) runLen;
        }
    }

    private void collectEncodedHll(byte[] regs, int encoded) {
        int index = decodeIndex(encoded, precision);
        int runLen = decodeRunLen(encoded, precision);
        if (regs[index] < runLen) {
            regs[index] = (byte) runLen;
        }
    }

    private void collectEncoded(int b, int encoded) {
        byte[] regs = registers[b];
        if (regs != null) {
            collectEncodedHll(regs, encoded);
            return;
        }
        int[] set = hashSets[b];
        if (set == null) {
            set = new int[Math.min(INITIAL_SET_CAPACITY, maxSetCapacity)];
            hashSets[b] = set;
        }
        int size = setSizes[b];
        if (size >= set.length * MAX_LOAD_FACTOR && set.length < maxSetCapacity) {
            set = rehash(set, set.length << 1);
            hashSets[b] = set;
        }
        if (size >= linearCountingThreshold) {
            if (contains(set, encoded)) {
                return;
            }
            regs = upgradeToHll(b);
            collectEncodedHll(regs, encoded);
            return;
        }
        if (insert(set, encoded)) {
            setSizes[b] = size + 1;
        }
    }

    private byte[] upgradeToHll(int b) {
        byte[] regs = new byte[m];
        int[] set = hashSets[b];
        if (set != null) {
            for (int v : set) {
                if (v != 0) {
                    collectEncodedHll(regs, v);
                }
            }
        }
        hashSets[b] = null;
        setSizes[b] = 0;
        registers[b] = regs;
        return regs;
    }

    private static int slot(int encoded, int mask) {
        int h = encoded;
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return h & mask;
    }

    private static boolean contains(int[] set, int encoded) {
        int mask = set.length - 1;
        int i = slot(encoded, mask);
        while (true) {
            int v = set[i];
            if (v == 0) {
                return false;
            }
            if (v == encoded) {
                return true;
            }
            i = (i + 1) & mask;
        }
    }

    private static boolean insert(int[] set, int encoded) {
        int mask = set.length - 1;
        int i = slot(encoded, mask);
        while (true) {
            int v = set[i];
            if (v == 0) {
                set[i] = encoded;
                return true;
            }
            if (v == encoded) {
                return false;
            }
            i = (i + 1) & mask;
        }
    }

    private static int[] rehash(int[] set, int newCapacity) {
        int[] n = new int[newCapacity];
        for (int v : set) {
            if (v != 0) {
                insert(n, v);
            }
        }
        return n;
    }

    static int encodeHash(long hash, int p) {
        long e = hash >>> (64 - P2);
        long encoded;
        if ((e & mask(P2 - p)) == 0) {
            int runLen = 1 + Math.min(Long.numberOfLeadingZeros(hash << P2), 64 - P2);
            encoded = (e << 7) | ((long) runLen << 1) | 1L;
        } else {
            encoded = e << 1;
        }
        return (int) encoded;
    }

    static int decodeIndex(int encoded, int p) {
        long index;
        if ((encoded & 1) == 1) {
            index = (encoded & 0xFFFFFFFFL) >>> 7;
        } else {
            index = (encoded & 0xFFFFFFFFL) >>> 1;
        }
        return (int) (index >>> (P2 - p));
    }

    static int decodeRunLen(int encoded, int p) {
        if ((encoded & 1) == 1) {
            return ((encoded >>> 1) & 0x3F) + (P2 - p);
        }
        int bits = encoded << (31 + p - P2);
        return 1 + Integer.numberOfLeadingZeros(bits);
    }

    private static long mask(int bits) {
        return (1L << bits) - 1;
    }

    public long cardinality(long bucket) {
        int b = checkBucket(bucket);
        if (b >= setSizes.length) {
            return 0;
        }
        byte[] regs = registers[b];
        if (regs == null) {
            return setSizes[b];
        }
        return Math.round(hllEstimate(regs));
    }

    private double hllEstimate(byte[] regs) {
        int q = 64 - precision;
        int[] c = new int[q + 2];
        for (byte r : regs) {
            c[r]++;
        }
        if (c[0] > 0) {
            double lc = m * Math.log((double) m / c[0]);
            if (lc <= THRESHOLDS[precision - MIN_PRECISION]) {
                return lc;
            }
        }
        double z = m * tau(1.0 - (double) c[q + 1] / m);
        for (int k = q; k >= 1; k--) {
            z += c[k];
            z *= 0.5;
        }
        z += m * sigma((double) c[0] / m);
        return ALPHA_INF * m * ((double) m / z);
    }

    private static double sigma(double x) {
        if (x == 1.0) {
            return Double.POSITIVE_INFINITY;
        }
        double y = 1.0;
        double z = x;
        double zPrev;
        do {
            x *= x;
            zPrev = z;
            z += x * y;
            y += y;
        } while (zPrev != z);
        return z;
    }

    private static double tau(double x) {
        if (x == 0.0 || x == 1.0) {
            return 0.0;
        }
        double y = 1.0;
        double z = 1.0 - x;
        double zPrev;
        do {
            x = Math.sqrt(x);
            zPrev = z;
            y *= 0.5;
            double d = 1.0 - x;
            z -= d * d * y;
        } while (zPrev != z);
        return z / 3.0;
    }

    public void merge(long thisBucket, HyperLogLogPlusPlus other, long otherBucket) {
        if (other.precision != precision) {
            throw new IllegalArgumentException("Cannot merge HyperLogLog++ sketches with different precisions: " + precision + " vs " + other.precision);
        }
        int b = checkBucket(thisBucket);
        int ob = checkBucket(otherBucket);
        if (ob >= other.setSizes.length) {
            return;
        }
        ensureBucket(b);
        byte[] otherRegs = other.registers[ob];
        if (otherRegs != null) {
            byte[] regs = registers[b];
            if (regs == null) {
                regs = upgradeToHll(b);
            }
            for (int i = 0; i < m; i++) {
                if (otherRegs[i] > regs[i]) {
                    regs[i] = otherRegs[i];
                }
            }
        } else {
            int[] otherSet = other.hashSets[ob];
            if (otherSet != null) {
                if (other == this && ob == b) {
                    return;
                }
                int[] copy = otherSet.clone();
                for (int v : copy) {
                    if (v != 0) {
                        collectEncoded(b, v);
                    }
                }
            }
        }
    }

    public void reset(long bucket) {
        int b = checkBucket(bucket);
        if (b < setSizes.length) {
            hashSets[b] = null;
            setSizes[b] = 0;
            registers[b] = null;
        }
    }

    public int[] linearCountingHashes(long bucket) {
        int b = checkBucket(bucket);
        if (b >= setSizes.length || registers[b] != null || hashSets[b] == null) {
            return new int[0];
        }
        int[] out = new int[setSizes[b]];
        int i = 0;
        for (int v : hashSets[b]) {
            if (v != 0) {
                out[i++] = v;
            }
        }
        return out;
    }

    public byte[] registers(long bucket) {
        int b = checkBucket(bucket);
        if (b >= setSizes.length) {
            return new byte[m];
        }
        byte[] regs = registers[b];
        if (regs != null) {
            return regs.clone();
        }
        byte[] out = new byte[m];
        int[] set = hashSets[b];
        if (set != null) {
            for (int v : set) {
                if (v != 0) {
                    collectEncodedHll(out, v);
                }
            }
        }
        return out;
    }

    public boolean equals(long bucket, HyperLogLogPlusPlus other, long otherBucket) {
        if (precision != other.precision) {
            return false;
        }
        if (algorithm(bucket) != other.algorithm(otherBucket)) {
            return false;
        }
        if (isLinearCounting(bucket)) {
            int[] a = linearCountingHashes(bucket);
            int[] c = other.linearCountingHashes(otherBucket);
            Arrays.sort(a);
            Arrays.sort(c);
            return Arrays.equals(a, c);
        }
        return Arrays.equals(registers(bucket), other.registers(otherBucket));
    }

    public void writeTo(long bucket, DataOutput out) throws IOException {
        out.writeByte(SERIAL_VERSION);
        out.writeByte(precision);
        if (isLinearCounting(bucket)) {
            out.writeByte(LINEAR_COUNTING);
            int[] hashes = linearCountingHashes(bucket);
            out.writeInt(hashes.length);
            for (int h : hashes) {
                out.writeInt(h);
            }
        } else {
            out.writeByte(HYPERLOGLOG);
            out.write(registers[(int) bucket]);
        }
    }

    public static HyperLogLogPlusPlus readFrom(DataInput in) throws IOException {
        int version = in.readUnsignedByte();
        if (version != SERIAL_VERSION) {
            throw new IOException("unsupported HyperLogLog++ serialization version: " + version);
        }
        int precision = in.readUnsignedByte();
        if (precision < MIN_PRECISION || precision > MAX_PRECISION) {
            throw new IOException("invalid HyperLogLog++ precision: " + precision);
        }
        HyperLogLogPlusPlus result = new HyperLogLogPlusPlus(precision, 1);
        int algorithm = in.readUnsignedByte();
        if (algorithm == LINEAR_COUNTING) {
            int size = in.readInt();
            if (size < 0 || size > result.m) {
                throw new IOException("invalid linear counting size: " + size);
            }
            for (int i = 0; i < size; i++) {
                int encoded = in.readInt();
                if (encoded == 0) {
                    throw new IOException("invalid encoded hash");
                }
                result.collectEncoded(0, encoded);
            }
        } else if (algorithm == HYPERLOGLOG) {
            byte[] regs = new byte[result.m];
            in.readFully(regs);
            int maxRun = 64 - precision + 1;
            for (byte r : regs) {
                if (r < 0 || r > maxRun) {
                    throw new IOException("invalid register value: " + r);
                }
            }
            result.registers[0] = regs;
        } else {
            throw new IOException("unknown HyperLogLog++ algorithm: " + algorithm);
        }
        return result;
    }

    public byte[] toBytes(long bucket) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            writeTo(bucket, out);
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static HyperLogLogPlusPlus fromBytes(byte[] bytes) {
        try {
            return readFrom(new DataInputStream(new ByteArrayInputStream(bytes)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
