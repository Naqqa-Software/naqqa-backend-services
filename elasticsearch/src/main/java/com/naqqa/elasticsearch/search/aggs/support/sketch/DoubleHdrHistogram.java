package com.naqqa.elasticsearch.search.aggs.support.sketch;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

public final class DoubleHdrHistogram {

    private static final int SERIAL_VERSION = 1;
    private static final int MAX_TOP_BIT = 61;

    private final int numberOfSignificantValueDigits;
    private final int subBucketCountMagnitude;
    private HdrHistogram histogram;
    private boolean hasScale;
    private int exponent;

    public DoubleHdrHistogram(int numberOfSignificantValueDigits) {
        this.numberOfSignificantValueDigits = numberOfSignificantValueDigits;
        this.histogram = new HdrHistogram(1, 2, numberOfSignificantValueDigits, true);
        this.subBucketCountMagnitude = Integer.numberOfTrailingZeros(histogram.subBucketCount());
    }

    private DoubleHdrHistogram(DoubleHdrHistogram source) {
        this.numberOfSignificantValueDigits = source.numberOfSignificantValueDigits;
        this.subBucketCountMagnitude = source.subBucketCountMagnitude;
        this.histogram = source.histogram.copy();
        this.hasScale = source.hasScale;
        this.exponent = source.exponent;
    }

    public DoubleHdrHistogram copy() {
        return new DoubleHdrHistogram(this);
    }

    public int numberOfSignificantValueDigits() {
        return numberOfSignificantValueDigits;
    }

    public double integerToDoubleValueConversionRatio() {
        return Math.scalb(1.0, exponent);
    }

    private static int exponentOf(double v) {
        if (v < Double.MIN_NORMAL) {
            long bits = Double.doubleToRawLongBits(v);
            return -1074 + (63 - Long.numberOfLeadingZeros(bits));
        }
        return Math.getExponent(v);
    }

    private long toInt(double v) {
        double scaled = Math.scalb(v, -exponent);
        if (scaled >= 9.2e18) {
            return Long.MAX_VALUE;
        }
        return (long) scaled;
    }

    private double toDouble(long v) {
        return Math.scalb((double) v, exponent);
    }

    private int topBit() {
        long max = histogram.getMaxValue();
        if (max <= 0) {
            return 0;
        }
        return 63 - Long.numberOfLeadingZeros(max);
    }

    private void rescaleTo(int newExponent) {
        if (newExponent == exponent) {
            return;
        }
        if (histogram.getTotalCount() > 0) {
            if (newExponent < exponent) {
                histogram = histogram.shiftedLeft(exponent - newExponent);
            } else {
                histogram = histogram.shiftedRight(newExponent - exponent);
            }
        }
        exponent = newExponent;
    }

    public void recordValue(double value) {
        recordValueWithCount(value, 1);
    }

    public void recordValueWithCount(double value, long count) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value < 0) {
            throw new IllegalArgumentException("DoubleHdrHistogram can only record finite non-negative values, got " + value);
        }
        if (count < 0) {
            throw new IllegalArgumentException("count cannot be negative: " + count);
        }
        if (count == 0) {
            return;
        }
        if (value == 0) {
            histogram.recordValueWithCount(0, count);
            return;
        }
        int ve = exponentOf(value);
        if (!hasScale) {
            hasScale = true;
            exponent = ve - subBucketCountMagnitude;
        } else {
            if (ve - exponent > MAX_TOP_BIT) {
                rescaleTo(ve - MAX_TOP_BIT);
            }
            if (ve - exponent < subBucketCountMagnitude) {
                int desired = ve - subBucketCountMagnitude;
                int lowest = exponent - (MAX_TOP_BIT - topBit());
                int target = Math.max(desired, lowest);
                if (target < exponent) {
                    rescaleTo(target);
                }
            }
        }
        histogram.recordValueWithCount(toInt(value), count);
    }

    public long getTotalCount() {
        return histogram.getTotalCount();
    }

    public double getValueAtPercentile(double percentile) {
        if (histogram.getTotalCount() == 0) {
            return 0.0;
        }
        return toDouble(histogram.getValueAtPercentile(percentile));
    }

    public double getPercentileAtOrBelowValue(double value) {
        if (histogram.getTotalCount() == 0) {
            return 100.0;
        }
        if (Double.isNaN(value)) {
            return Double.NaN;
        }
        if (value < 0) {
            return 0.0;
        }
        return histogram.getPercentileAtOrBelowValue(toInt(value));
    }

    public double getMinValue() {
        return toDouble(histogram.getMinValue());
    }

    public double getMinNonZeroValue() {
        long v = histogram.getMinNonZeroValue();
        return v == Long.MAX_VALUE ? Double.MAX_VALUE : toDouble(v);
    }

    public double getMaxValue() {
        long v = histogram.getMaxValue();
        if (v == 0) {
            return 0.0;
        }
        return highestEquivalentValue(toDouble(histogram.lowestEquivalentValue(v)));
    }

    public double getMean() {
        return histogram.getMean() * integerToDoubleValueConversionRatio();
    }

    public double getStdDeviation() {
        return histogram.getStdDeviation() * integerToDoubleValueConversionRatio();
    }

    public double getCountAtValue(double value) {
        return histogram.getCountAtValue(toInt(value));
    }

    public double sizeOfEquivalentValueRange(double value) {
        return toDouble(histogram.sizeOfEquivalentValueRange(toInt(value)));
    }

    public double lowestEquivalentValue(double value) {
        return toDouble(histogram.lowestEquivalentValue(toInt(value)));
    }

    public double nextNonEquivalentValue(double value) {
        return toDouble(histogram.nextNonEquivalentValue(toInt(value)));
    }

    public double highestEquivalentValue(double value) {
        return Math.nextDown(nextNonEquivalentValue(value));
    }

    public double medianEquivalentValue(double value) {
        long i = toInt(value);
        return toDouble(histogram.lowestEquivalentValue(i)) + toDouble(histogram.sizeOfEquivalentValueRange(i)) / 2.0;
    }

    public boolean valuesAreEquivalent(double value1, double value2) {
        return lowestEquivalentValue(value1) == lowestEquivalentValue(value2);
    }

    public void add(DoubleHdrHistogram other) {
        if (other == this) {
            add(other.copy());
            return;
        }
        if (other.histogram.getTotalCount() == 0) {
            return;
        }
        if (!other.hasScale) {
            histogram.add(other.histogram);
            return;
        }
        if (!hasScale) {
            hasScale = true;
            exponent = other.exponent;
            HdrHistogram merged = other.histogram.copy();
            merged.add(histogram);
            histogram = merged;
            return;
        }
        int lowerBoundThis = exponent - (MAX_TOP_BIT - topBit());
        int lowerBoundOther = other.exponent - (MAX_TOP_BIT - other.topBit());
        int target = Math.max(Math.min(exponent, other.exponent), Math.max(lowerBoundThis, lowerBoundOther));
        rescaleTo(target);
        HdrHistogram incoming;
        if (other.exponent == target) {
            incoming = other.histogram;
        } else if (other.exponent > target) {
            incoming = other.histogram.shiftedLeft(other.exponent - target);
        } else {
            incoming = other.histogram.shiftedRight(target - other.exponent);
        }
        histogram.add(incoming);
    }

    public void reset() {
        histogram = new HdrHistogram(1, 2, numberOfSignificantValueDigits, true);
        hasScale = false;
        exponent = 0;
    }

    public void writeTo(DataOutput out) throws IOException {
        out.writeByte(SERIAL_VERSION);
        out.writeByte(numberOfSignificantValueDigits);
        out.writeBoolean(hasScale);
        out.writeInt(exponent);
        histogram.writeTo(out);
    }

    public static DoubleHdrHistogram readFrom(DataInput in) throws IOException {
        int version = in.readUnsignedByte();
        if (version != SERIAL_VERSION) {
            throw new IOException("unsupported double HDR histogram serialization version: " + version);
        }
        int digits = in.readUnsignedByte();
        if (digits > 5) {
            throw new IOException("invalid number of significant digits: " + digits);
        }
        DoubleHdrHistogram d = new DoubleHdrHistogram(digits);
        d.hasScale = in.readBoolean();
        d.exponent = in.readInt();
        HdrHistogram h = HdrHistogram.readFrom(in);
        if (h.numberOfSignificantValueDigits() != digits || h.lowestDiscernibleValue() != 1 || !h.isAutoResize()) {
            throw new IOException("inconsistent double HDR histogram payload");
        }
        d.histogram = h;
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

    public static DoubleHdrHistogram fromBytes(byte[] bytes) {
        try {
            return readFrom(new DataInputStream(new ByteArrayInputStream(bytes)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
