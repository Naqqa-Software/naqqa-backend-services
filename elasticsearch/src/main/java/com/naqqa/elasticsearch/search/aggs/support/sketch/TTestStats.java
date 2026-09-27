package com.naqqa.elasticsearch.search.aggs.support.sketch;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public final class TTestStats {

    private long count;
    private double mean;
    private double m2;

    public TTestStats() {
    }

    public TTestStats(long count, double mean, double m2) {
        if (count < 0) {
            throw new IllegalArgumentException("count must be >= 0");
        }
        this.count = count;
        this.mean = count == 0 ? 0 : mean;
        this.m2 = count == 0 ? 0 : m2;
    }

    public static TTestStats fromSums(long count, double sum, double sumOfSquares) {
        if (count == 0) {
            return new TTestStats();
        }
        double mean = sum / count;
        double m2 = Math.max(0, sumOfSquares - sum * mean);
        return new TTestStats(count, mean, m2);
    }

    public void add(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("value must be finite, got " + value);
        }
        count++;
        double delta = value - mean;
        mean += delta / count;
        m2 += delta * (value - mean);
    }

    public void merge(TTestStats other) {
        if (other.count == 0) {
            return;
        }
        if (count == 0) {
            count = other.count;
            mean = other.mean;
            m2 = other.m2;
            return;
        }
        long n = count + other.count;
        double delta = other.mean - mean;
        double newMean = mean + delta * ((double) other.count / n);
        double newM2 = m2 + other.m2 + delta * delta * ((double) count * other.count / n);
        count = n;
        mean = newMean;
        m2 = newM2;
    }

    public TTestStats copy() {
        return new TTestStats(count, mean, m2);
    }

    public long count() {
        return count;
    }

    public double mean() {
        return count == 0 ? Double.NaN : mean;
    }

    public double sum() {
        return mean * count;
    }

    public double sumOfSquares() {
        return m2 + mean * mean * count;
    }

    public double m2() {
        return m2;
    }

    public double variance() {
        return count < 2 ? Double.NaN : m2 / (count - 1);
    }

    public void writeTo(DataOutput out) throws IOException {
        out.writeLong(count);
        out.writeDouble(mean);
        out.writeDouble(m2);
    }

    public static TTestStats readFrom(DataInput in) throws IOException {
        long count = in.readLong();
        double mean = in.readDouble();
        double m2 = in.readDouble();
        if (count < 0) {
            throw new IOException("invalid count: " + count);
        }
        return new TTestStats(count, mean, m2);
    }
}
