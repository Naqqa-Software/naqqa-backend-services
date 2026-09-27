package com.naqqa.elasticsearch.search.aggs.support.sketch;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public final class TDigest {

    public static final double DEFAULT_COMPRESSION = 100.0;

    private static final int SERIAL_VERSION = 1;

    public record Centroid(double mean, double weight) {
        public long count() {
            return Math.round(weight);
        }
    }

    private final double compression;
    private double[] mean;
    private double[] weight;
    private int lastUsedCell;
    private double totalWeight;
    private final double[] tempMean;
    private final double[] tempWeight;
    private int tempUsed;
    private double unmergedWeight;
    private double min = Double.POSITIVE_INFINITY;
    private double max = Double.NEGATIVE_INFINITY;
    private boolean runBackwards;

    public TDigest() {
        this(DEFAULT_COMPRESSION);
    }

    public TDigest(double compression) {
        if (!(compression > 0) || Double.isInfinite(compression)) {
            throw new IllegalArgumentException("compression must be a positive finite number, got " + compression);
        }
        this.compression = compression;
        int size = (int) (2 * Math.ceil(compression)) + 10;
        this.mean = new double[size];
        this.weight = new double[size];
        int bufferSize = 5 * size;
        this.tempMean = new double[bufferSize];
        this.tempWeight = new double[bufferSize];
    }

    public double compression() {
        return compression;
    }

    public void add(double x) {
        add(x, 1.0);
    }

    public void add(double x, double w) {
        if (Double.isNaN(x)) {
            throw new IllegalArgumentException("Cannot add NaN to t-digest");
        }
        if (!(w > 0) || Double.isInfinite(w)) {
            throw new IllegalArgumentException("weight must be positive and finite, got " + w);
        }
        if (tempUsed >= tempMean.length) {
            mergeNewValues(false);
        }
        tempMean[tempUsed] = x;
        tempWeight[tempUsed] = w;
        tempUsed++;
        unmergedWeight += w;
        if (x < min) {
            min = x;
        }
        if (x > max) {
            max = x;
        }
    }

    public void add(TDigest other) {
        if (other == this) {
            List<Centroid> copy = centroids();
            for (Centroid c : copy) {
                add(c.mean(), c.weight());
            }
            return;
        }
        List<Centroid> cs = other.centroids();
        for (Centroid c : cs) {
            add(c.mean(), c.weight());
        }
        if (!cs.isEmpty()) {
            min = Math.min(min, other.min);
            max = Math.max(max, other.max);
        }
    }

    public void merge(TDigest other) {
        add(other);
    }

    public static TDigest mergeAll(double compression, Collection<TDigest> digests) {
        TDigest result = new TDigest(compression);
        for (TDigest d : digests) {
            result.add(d);
        }
        result.compress();
        return result;
    }

    public void compress() {
        mergeNewValues(true);
    }

    private void mergeNewValues(boolean force) {
        if (totalWeight == 0 && unmergedWeight == 0) {
            return;
        }
        if (tempUsed == 0 && !force) {
            return;
        }
        int n = tempUsed + lastUsedCell;
        double[] inMean = new double[n];
        double[] inWeight = new double[n];
        System.arraycopy(tempMean, 0, inMean, 0, tempUsed);
        System.arraycopy(tempWeight, 0, inWeight, 0, tempUsed);
        System.arraycopy(mean, 0, inMean, tempUsed, lastUsedCell);
        System.arraycopy(weight, 0, inWeight, tempUsed, lastUsedCell);
        tempUsed = 0;
        sort(inMean, inWeight, 0, n - 1);
        if (runBackwards) {
            reverse(inMean, n);
            reverse(inWeight, n);
        }
        totalWeight += unmergedWeight;
        unmergedWeight = 0;
        double[] outMean = mean.length >= n ? mean : new double[n];
        double[] outWeight = weight.length >= n ? weight : new double[n];
        int last = 0;
        outMean[0] = inMean[0];
        outWeight[0] = inWeight[0];
        double wSoFar = 0;
        double normalizer = normalizer(compression, totalWeight);
        for (int i = 1; i < n; i++) {
            double proposedWeight = outWeight[last] + inWeight[i];
            double q0 = wSoFar / totalWeight;
            double q2 = (wSoFar + proposedWeight) / totalWeight;
            boolean addThis = proposedWeight <= totalWeight * Math.min(maxWeight(q0, normalizer), maxWeight(q2, normalizer));
            if (i == 1 || i == n - 1) {
                addThis = false;
            }
            if (addThis) {
                outWeight[last] += inWeight[i];
                outMean[last] = outMean[last] + (inMean[i] - outMean[last]) * inWeight[i] / outWeight[last];
            } else {
                wSoFar += outWeight[last];
                last++;
                outMean[last] = inMean[i];
                outWeight[last] = inWeight[i];
            }
        }
        last++;
        if (runBackwards) {
            reverse(outMean, last);
            reverse(outWeight, last);
        }
        mean = outMean;
        weight = outWeight;
        lastUsedCell = last;
        runBackwards = !runBackwards;
        if (lastUsedCell > 0) {
            min = Math.min(min, mean[0]);
            max = Math.max(max, mean[lastUsedCell - 1]);
        }
    }

    private static double normalizer(double compression, double n) {
        double z = 4 * Math.log(n / compression) + 24;
        if (z < 1) {
            z = 1;
        }
        return compression / z;
    }

    private static double maxWeight(double q, double normalizer) {
        return q * (1 - q) / normalizer;
    }

    private static void reverse(double[] a, int n) {
        for (int i = 0, j = n - 1; i < j; i++, j--) {
            double t = a[i];
            a[i] = a[j];
            a[j] = t;
        }
    }

    private static void sort(double[] keys, double[] values, int lo, int hi) {
        while (hi - lo > 16) {
            int mid = (lo + hi) >>> 1;
            if (keys[mid] < keys[lo]) {
                swap(keys, values, mid, lo);
            }
            if (keys[hi] < keys[lo]) {
                swap(keys, values, hi, lo);
            }
            if (keys[hi] < keys[mid]) {
                swap(keys, values, hi, mid);
            }
            double pivot = keys[mid];
            int i = lo;
            int j = hi;
            while (i <= j) {
                while (keys[i] < pivot) {
                    i++;
                }
                while (keys[j] > pivot) {
                    j--;
                }
                if (i <= j) {
                    swap(keys, values, i, j);
                    i++;
                    j--;
                }
            }
            if (j - lo < hi - i) {
                sort(keys, values, lo, j);
                lo = i;
            } else {
                sort(keys, values, i, hi);
                hi = j;
            }
        }
        for (int i = lo + 1; i <= hi; i++) {
            double k = keys[i];
            double v = values[i];
            int j = i - 1;
            while (j >= lo && keys[j] > k) {
                keys[j + 1] = keys[j];
                values[j + 1] = values[j];
                j--;
            }
            keys[j + 1] = k;
            values[j + 1] = v;
        }
    }

    private static void swap(double[] keys, double[] values, int i, int j) {
        double k = keys[i];
        keys[i] = keys[j];
        keys[j] = k;
        double v = values[i];
        values[i] = values[j];
        values[j] = v;
    }

    public long size() {
        return Math.round(totalWeight + unmergedWeight);
    }

    public double totalWeight() {
        return totalWeight + unmergedWeight;
    }

    public double getMin() {
        return min;
    }

    public double getMax() {
        return max;
    }

    public int centroidCount() {
        mergeNewValues(false);
        return lastUsedCell;
    }

    public List<Centroid> centroids() {
        mergeNewValues(false);
        List<Centroid> list = new ArrayList<>(lastUsedCell);
        for (int i = 0; i < lastUsedCell; i++) {
            list.add(new Centroid(mean[i], weight[i]));
        }
        return Collections.unmodifiableList(list);
    }

    public double quantile(double q) {
        if (q < 0 || q > 1 || Double.isNaN(q)) {
            throw new IllegalArgumentException("q should be in [0,1], got " + q);
        }
        mergeNewValues(false);
        int n = lastUsedCell;
        if (n == 0) {
            return Double.NaN;
        }
        if (n == 1) {
            return mean[0];
        }
        double index = q * totalWeight;
        if (index < 1) {
            return min;
        }
        if (weight[0] > 1 && index < weight[0] / 2) {
            return min + (index - 1) / (weight[0] / 2 - 1) * (mean[0] - min);
        }
        if (index > totalWeight - 1) {
            return max;
        }
        if (weight[n - 1] > 1 && totalWeight - index <= weight[n - 1] / 2) {
            return max - (totalWeight - index - 1) / (weight[n - 1] / 2 - 1) * (max - mean[n - 1]);
        }
        double weightSoFar = weight[0] / 2;
        for (int i = 0; i < n - 1; i++) {
            double dw = (weight[i] + weight[i + 1]) / 2;
            if (weightSoFar + dw > index) {
                double leftUnit = 0;
                if (weight[i] == 1) {
                    if (index - weightSoFar < 0.5) {
                        return mean[i];
                    }
                    leftUnit = 0.5;
                }
                double rightUnit = 0;
                if (weight[i + 1] == 1) {
                    if (weightSoFar + dw - index <= 0.5) {
                        return mean[i + 1];
                    }
                    rightUnit = 0.5;
                }
                double z1 = index - weightSoFar - leftUnit;
                double z2 = weightSoFar + dw - index - rightUnit;
                return weightedAverage(mean[i], z2, mean[i + 1], z1);
            }
            weightSoFar += dw;
        }
        double z1 = index - totalWeight - weight[n - 1] / 2.0;
        double z2 = weight[n - 1] / 2 - z1;
        return weightedAverage(mean[n - 1], z1, max, z2);
    }

    private static double weightedAverage(double x1, double w1, double x2, double w2) {
        if (x1 <= x2) {
            return weightedAverageSorted(x1, w1, x2, w2);
        }
        return weightedAverageSorted(x2, w2, x1, w1);
    }

    private static double weightedAverageSorted(double x1, double w1, double x2, double w2) {
        double total = w1 + w2;
        if (total <= 0) {
            return (x1 + x2) / 2;
        }
        double x = (x1 * w1 + x2 * w2) / total;
        return Math.max(x1, Math.min(x, x2));
    }

    public double cdf(double x) {
        if (Double.isNaN(x)) {
            return Double.NaN;
        }
        mergeNewValues(false);
        int n = lastUsedCell;
        if (n == 0) {
            return Double.NaN;
        }
        if (x < min) {
            return 0;
        }
        if (x > max) {
            return 1;
        }
        if (n == 1) {
            if (max - min < Double.MIN_NORMAL) {
                return 0.5;
            }
            return (x - min) / (max - min);
        }
        if (x < mean[0]) {
            if (mean[0] - min > 0) {
                if (x == min) {
                    return 0.5 / totalWeight;
                }
                return (1 + (x - min) / (mean[0] - min) * (weight[0] / 2 - 1)) / totalWeight;
            }
            return 0;
        }
        if (x > mean[n - 1]) {
            if (max - mean[n - 1] > 0) {
                if (x == max) {
                    return 1 - 0.5 / totalWeight;
                }
                double dq = (1 + (max - x) / (max - mean[n - 1]) * (weight[n - 1] / 2 - 1)) / totalWeight;
                return 1 - dq;
            }
            return 1;
        }
        double weightSoFar = 0;
        for (int it = 0; it < n - 1; it++) {
            if (mean[it] == x) {
                double dw = 0;
                while (it < n && mean[it] == x) {
                    dw += weight[it];
                    it++;
                }
                return (weightSoFar + dw / 2) / totalWeight;
            } else if (mean[it] <= x && x < mean[it + 1]) {
                if (mean[it + 1] - mean[it] > 0) {
                    double leftExcludedW = 0;
                    double rightExcludedW = 0;
                    if (weight[it] == 1) {
                        if (weight[it + 1] == 1) {
                            return (weightSoFar + 1) / totalWeight;
                        }
                        leftExcludedW = 0.5;
                    } else if (weight[it + 1] == 1) {
                        rightExcludedW = 0.5;
                    }
                    double dw = (weight[it] + weight[it + 1]) / 2;
                    double left = mean[it];
                    double right = mean[it + 1];
                    double dwNoSingleton = dw - leftExcludedW - rightExcludedW;
                    double base = weightSoFar + weight[it] / 2 + leftExcludedW;
                    return (base + dwNoSingleton * (x - left) / (right - left)) / totalWeight;
                }
                double dw = (weight[it] + weight[it + 1]) / 2;
                return (weightSoFar + dw) / totalWeight;
            } else {
                weightSoFar += weight[it];
            }
        }
        return 1 - 0.5 / totalWeight;
    }

    public void writeTo(DataOutput out) throws IOException {
        mergeNewValues(false);
        out.writeByte(SERIAL_VERSION);
        out.writeDouble(compression);
        out.writeDouble(min);
        out.writeDouble(max);
        out.writeInt(lastUsedCell);
        for (int i = 0; i < lastUsedCell; i++) {
            out.writeDouble(mean[i]);
            out.writeDouble(weight[i]);
        }
    }

    public static TDigest readFrom(DataInput in) throws IOException {
        int version = in.readUnsignedByte();
        if (version != SERIAL_VERSION) {
            throw new IOException("unsupported t-digest serialization version: " + version);
        }
        double compression = in.readDouble();
        if (!(compression > 0) || Double.isInfinite(compression)) {
            throw new IOException("invalid compression: " + compression);
        }
        TDigest d = new TDigest(compression);
        double min = in.readDouble();
        double max = in.readDouble();
        int n = in.readInt();
        if (n < 0) {
            throw new IOException("invalid centroid count: " + n);
        }
        if (d.mean.length < n) {
            d.mean = new double[n];
            d.weight = new double[n];
        }
        double total = 0;
        double prev = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            double m = in.readDouble();
            double w = in.readDouble();
            if (Double.isNaN(m) || !(w > 0) || m < prev) {
                throw new IOException("invalid centroid at " + i);
            }
            prev = m;
            d.mean[i] = m;
            d.weight[i] = w;
            total += w;
        }
        d.lastUsedCell = n;
        d.totalWeight = total;
        d.min = min;
        d.max = max;
        return d;
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

    public static TDigest fromBytes(byte[] bytes) {
        try {
            return readFrom(new DataInputStream(new ByteArrayInputStream(bytes)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
