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
import java.util.SplittableRandom;

public final class ReservoirSampler {

    private static final int SERIAL_VERSION = 1;

    private final int capacity;
    private final double[] sample;
    private int size;
    private long count;
    private double sum;
    private double sumCompensation;
    private double min = Double.POSITIVE_INFINITY;
    private double max = Double.NEGATIVE_INFINITY;
    private final SplittableRandom random;

    public ReservoirSampler(int capacity) {
        this(capacity, 0x5DEECE66DL);
    }

    public ReservoirSampler(int capacity, long seed) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1, got " + capacity);
        }
        this.capacity = capacity;
        this.sample = new double[capacity];
        this.random = new SplittableRandom(seed);
    }

    public int capacity() {
        return capacity;
    }

    public int sampleSize() {
        return size;
    }

    public long count() {
        return count;
    }

    public double sum() {
        return sum;
    }

    public double mean() {
        return count == 0 ? Double.NaN : sum / count;
    }

    public double min() {
        return min;
    }

    public double max() {
        return max;
    }

    public double[] sample() {
        return Arrays.copyOf(sample, size);
    }

    public double[] sortedSample() {
        double[] s = sample();
        Arrays.sort(s);
        return s;
    }

    public void add(double value) {
        if (Double.isNaN(value)) {
            throw new IllegalArgumentException("Cannot add NaN to reservoir");
        }
        count++;
        addToSum(value);
        if (value < min) {
            min = value;
        }
        if (value > max) {
            max = value;
        }
        if (size < capacity) {
            sample[size++] = value;
        } else {
            long j = random.nextLong(count);
            if (j < capacity) {
                sample[(int) j] = value;
            }
        }
    }

    private void addToSum(double value) {
        double y = value - sumCompensation;
        double t = sum + y;
        sumCompensation = (t - sum) - y;
        sum = t;
    }

    public void merge(ReservoirSampler other) {
        if (other == this) {
            throw new IllegalArgumentException("cannot merge a reservoir with itself");
        }
        if (other.count == 0) {
            return;
        }
        if (count == 0) {
            int take = Math.min(capacity, other.size);
            double[] pool = other.sample();
            shufflePrefix(pool, take);
            System.arraycopy(pool, 0, sample, 0, take);
            size = take;
            copyStats(other);
            return;
        }
        double[] poolA = sample();
        double[] poolB = other.sample();
        int remA = poolA.length;
        int remB = poolB.length;
        long nA = count;
        long nB = other.count;
        int target = (int) Math.min(capacity, Math.min(nA + nB, (long) remA + remB));
        double[] result = new double[capacity];
        int k = 0;
        while (k < target) {
            boolean fromA;
            if (remA == 0) {
                fromA = false;
            } else if (remB == 0) {
                fromA = true;
            } else {
                fromA = random.nextLong(nA + nB) < nA;
            }
            if (fromA) {
                int idx = random.nextInt(remA);
                result[k++] = poolA[idx];
                poolA[idx] = poolA[--remA];
                nA--;
            } else {
                int idx = random.nextInt(remB);
                result[k++] = poolB[idx];
                poolB[idx] = poolB[--remB];
                nB--;
            }
        }
        System.arraycopy(result, 0, sample, 0, k);
        size = k;
        count += other.count;
        addToSum(other.sum);
        min = Math.min(min, other.min);
        max = Math.max(max, other.max);
    }

    private void copyStats(ReservoirSampler other) {
        count = other.count;
        sum = other.sum;
        sumCompensation = other.sumCompensation;
        min = other.min;
        max = other.max;
    }

    private void shufflePrefix(double[] a, int k) {
        for (int i = 0; i < k; i++) {
            int j = i + random.nextInt(a.length - i);
            double t = a[i];
            a[i] = a[j];
            a[j] = t;
        }
    }

    public double quantile(double q) {
        if (q < 0 || q > 1 || Double.isNaN(q)) {
            throw new IllegalArgumentException("q should be in [0,1], got " + q);
        }
        if (size == 0) {
            return Double.NaN;
        }
        if (q == 0) {
            return min;
        }
        if (q == 1) {
            return max;
        }
        double[] s = sortedSample();
        double pos = q * (s.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = Math.min(lo + 1, s.length - 1);
        double frac = pos - lo;
        return s[lo] + (s[hi] - s[lo]) * frac;
    }

    public Boxplot.Stats boxplot() {
        if (size == 0) {
            double nan = Double.NaN;
            return new Boxplot.Stats(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, nan, nan, nan, nan, nan);
        }
        double[] s = sortedSample();
        double q1 = quantileSorted(s, 0.25);
        double q2 = quantileSorted(s, 0.5);
        double q3 = quantileSorted(s, 0.75);
        double iqr = q3 - q1;
        double lowerFence = q1 - 1.5 * iqr;
        double upperFence = q3 + 1.5 * iqr;
        double lower = min >= lowerFence ? min : q1;
        if (min < lowerFence) {
            for (double v : s) {
                if (v >= lowerFence) {
                    lower = Math.min(v, q1);
                    break;
                }
            }
        }
        double upper = max <= upperFence ? max : q3;
        if (max > upperFence) {
            for (int i = s.length - 1; i >= 0; i--) {
                if (s[i] <= upperFence) {
                    upper = Math.max(s[i], q3);
                    break;
                }
            }
        }
        return new Boxplot.Stats(min, max, q1, q2, q3, lower, upper);
    }

    private static double quantileSorted(double[] s, double q) {
        double pos = q * (s.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = Math.min(lo + 1, s.length - 1);
        return s[lo] + (s[hi] - s[lo]) * (pos - lo);
    }

    public void writeTo(DataOutput out) throws IOException {
        out.writeByte(SERIAL_VERSION);
        out.writeInt(capacity);
        out.writeLong(count);
        out.writeDouble(sum);
        out.writeDouble(sumCompensation);
        out.writeDouble(min);
        out.writeDouble(max);
        out.writeInt(size);
        for (int i = 0; i < size; i++) {
            out.writeDouble(sample[i]);
        }
    }

    public static ReservoirSampler readFrom(DataInput in, long seed) throws IOException {
        int version = in.readUnsignedByte();
        if (version != SERIAL_VERSION) {
            throw new IOException("unsupported reservoir serialization version: " + version);
        }
        int capacity = in.readInt();
        if (capacity < 1) {
            throw new IOException("invalid capacity: " + capacity);
        }
        ReservoirSampler r = new ReservoirSampler(capacity, seed);
        r.count = in.readLong();
        r.sum = in.readDouble();
        r.sumCompensation = in.readDouble();
        r.min = in.readDouble();
        r.max = in.readDouble();
        int size = in.readInt();
        if (size < 0 || size > capacity || size > r.count) {
            throw new IOException("invalid sample size: " + size);
        }
        for (int i = 0; i < size; i++) {
            r.sample[i] = in.readDouble();
        }
        r.size = size;
        return r;
    }

    public byte[] toBytes() {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            writeTo(out);
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static ReservoirSampler fromBytes(byte[] bytes, long seed) {
        try {
            return readFrom(new DataInputStream(new ByteArrayInputStream(bytes)), seed);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
