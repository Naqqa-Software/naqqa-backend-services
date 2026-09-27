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

public final class HdrHistogram {

    private static final int SERIAL_VERSION = 1;

    private final long lowestDiscernibleValue;
    private final int numberOfSignificantValueDigits;
    private final boolean autoResize;
    private final int unitMagnitude;
    private final int subBucketHalfCountMagnitude;
    private final int subBucketCount;
    private final int subBucketHalfCount;
    private final long subBucketMask;
    private final int leadingZeroCountBase;

    private long highestTrackableValue;
    private int bucketCount;
    private long[] counts;
    private long totalCount;
    private long maxValue;
    private long minNonZeroValue = Long.MAX_VALUE;

    public HdrHistogram(int numberOfSignificantValueDigits) {
        this(1, 2, numberOfSignificantValueDigits, true);
    }

    public HdrHistogram(long highestTrackableValue, int numberOfSignificantValueDigits) {
        this(1, highestTrackableValue, numberOfSignificantValueDigits, false);
    }

    public HdrHistogram(long lowestDiscernibleValue, long highestTrackableValue, int numberOfSignificantValueDigits, boolean autoResize) {
        if (lowestDiscernibleValue < 1) {
            throw new IllegalArgumentException("lowestDiscernibleValue must be >= 1");
        }
        if (highestTrackableValue < 2 * lowestDiscernibleValue) {
            throw new IllegalArgumentException("highestTrackableValue must be >= 2 * lowestDiscernibleValue");
        }
        if (numberOfSignificantValueDigits < 0 || numberOfSignificantValueDigits > 5) {
            throw new IllegalArgumentException("numberOfSignificantValueDigits must be between 0 and 5");
        }
        this.lowestDiscernibleValue = lowestDiscernibleValue;
        this.numberOfSignificantValueDigits = numberOfSignificantValueDigits;
        this.autoResize = autoResize;
        long largestValueWithSingleUnitResolution = 2 * (long) Math.pow(10, numberOfSignificantValueDigits);
        this.unitMagnitude = (int) (Math.log(lowestDiscernibleValue) / Math.log(2));
        int subBucketCountMagnitude = (int) Math.ceil(Math.log(largestValueWithSingleUnitResolution) / Math.log(2));
        this.subBucketHalfCountMagnitude = (subBucketCountMagnitude > 1 ? subBucketCountMagnitude : 1) - 1;
        this.subBucketCount = 1 << (subBucketHalfCountMagnitude + 1);
        this.subBucketHalfCount = subBucketCount / 2;
        this.subBucketMask = ((long) subBucketCount - 1) << unitMagnitude;
        if (unitMagnitude + subBucketHalfCountMagnitude > 61) {
            throw new IllegalArgumentException("cannot represent numberOfSignificantValueDigits with lowestDiscernibleValue");
        }
        this.leadingZeroCountBase = 64 - unitMagnitude - subBucketHalfCountMagnitude - 1;
        establishSize(highestTrackableValue);
        this.counts = new long[countsArrayLengthFor(bucketCount)];
    }

    private HdrHistogram(HdrHistogram source) {
        this.lowestDiscernibleValue = source.lowestDiscernibleValue;
        this.numberOfSignificantValueDigits = source.numberOfSignificantValueDigits;
        this.autoResize = source.autoResize;
        this.unitMagnitude = source.unitMagnitude;
        this.subBucketHalfCountMagnitude = source.subBucketHalfCountMagnitude;
        this.subBucketCount = source.subBucketCount;
        this.subBucketHalfCount = source.subBucketHalfCount;
        this.subBucketMask = source.subBucketMask;
        this.leadingZeroCountBase = source.leadingZeroCountBase;
        this.highestTrackableValue = source.highestTrackableValue;
        this.bucketCount = source.bucketCount;
        this.counts = source.counts.clone();
        this.totalCount = source.totalCount;
        this.maxValue = source.maxValue;
        this.minNonZeroValue = source.minNonZeroValue;
    }

    public HdrHistogram copy() {
        return new HdrHistogram(this);
    }

    private void establishSize(long newHighestTrackableValue) {
        this.bucketCount = getBucketsNeededToCoverValue(newHighestTrackableValue);
        int len = countsArrayLengthFor(bucketCount);
        long h = highestEquivalentValue(valueFromIndex(len - 1));
        this.highestTrackableValue = h < 0 ? Long.MAX_VALUE : Math.max(h, newHighestTrackableValue);
    }

    private int countsArrayLengthFor(int buckets) {
        return (buckets + 1) * (subBucketCount / 2);
    }

    private int getBucketsNeededToCoverValue(long value) {
        long smallestUntrackableValue = ((long) subBucketCount) << unitMagnitude;
        int bucketsNeeded = 1;
        while (smallestUntrackableValue <= value) {
            if (smallestUntrackableValue > (Long.MAX_VALUE / 2)) {
                return bucketsNeeded + 1;
            }
            smallestUntrackableValue <<= 1;
            bucketsNeeded++;
        }
        return bucketsNeeded;
    }

    public int numberOfSignificantValueDigits() {
        return numberOfSignificantValueDigits;
    }

    public long lowestDiscernibleValue() {
        return lowestDiscernibleValue;
    }

    public long highestTrackableValue() {
        return highestTrackableValue;
    }

    public boolean isAutoResize() {
        return autoResize;
    }

    public int subBucketCount() {
        return subBucketCount;
    }

    int countsArrayLength() {
        return counts.length;
    }

    private int getBucketIndex(long value) {
        return leadingZeroCountBase - Long.numberOfLeadingZeros(value | subBucketMask);
    }

    private int getSubBucketIndex(long value, int bucketIndex) {
        return (int) (value >>> (bucketIndex + unitMagnitude));
    }

    int countsArrayIndex(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("Histogram recorded value cannot be negative: " + value);
        }
        int bucketIndex = getBucketIndex(value);
        int subBucketIndex = getSubBucketIndex(value, bucketIndex);
        int bucketBaseIndex = (bucketIndex + 1) << subBucketHalfCountMagnitude;
        int offsetInBucket = subBucketIndex - subBucketHalfCount;
        return bucketBaseIndex + offsetInBucket;
    }

    private long valueFromIndex(int bucketIndex, int subBucketIndex) {
        return ((long) subBucketIndex) << (bucketIndex + unitMagnitude);
    }

    long valueFromIndex(int index) {
        int bucketIndex = (index >> subBucketHalfCountMagnitude) - 1;
        int subBucketIndex = (index & (subBucketHalfCount - 1)) + subBucketHalfCount;
        if (bucketIndex < 0) {
            subBucketIndex -= subBucketHalfCount;
            bucketIndex = 0;
        }
        return valueFromIndex(bucketIndex, subBucketIndex);
    }

    public long sizeOfEquivalentValueRange(long value) {
        int bucketIndex = getBucketIndex(value);
        int subBucketIndex = getSubBucketIndex(value, bucketIndex);
        int adjustedBucket = (subBucketIndex >= subBucketCount) ? (bucketIndex + 1) : bucketIndex;
        return 1L << (unitMagnitude + adjustedBucket);
    }

    public long lowestEquivalentValue(long value) {
        int bucketIndex = getBucketIndex(value);
        int subBucketIndex = getSubBucketIndex(value, bucketIndex);
        return valueFromIndex(bucketIndex, subBucketIndex);
    }

    public long nextNonEquivalentValue(long value) {
        return lowestEquivalentValue(value) + sizeOfEquivalentValueRange(value);
    }

    public long highestEquivalentValue(long value) {
        return nextNonEquivalentValue(value) - 1;
    }

    public long medianEquivalentValue(long value) {
        return lowestEquivalentValue(value) + (sizeOfEquivalentValueRange(value) >> 1);
    }

    public boolean valuesAreEquivalent(long value1, long value2) {
        return lowestEquivalentValue(value1) == lowestEquivalentValue(value2);
    }

    public void recordValue(long value) {
        recordValueWithCount(value, 1);
    }

    public void recordValueWithCount(long value, long count) {
        if (value < 0) {
            throw new IllegalArgumentException("Histogram recorded value cannot be negative: " + value);
        }
        if (count < 0) {
            throw new IllegalArgumentException("count cannot be negative: " + count);
        }
        if (count == 0) {
            return;
        }
        int index = countsArrayIndex(value);
        if (index >= counts.length) {
            if (!autoResize) {
                throw new IndexOutOfBoundsException("value " + value + " outside of histogram covered range " + highestTrackableValue);
            }
            resize(value);
        }
        counts[index] += count;
        totalCount += count;
        if (value > maxValue) {
            maxValue = value;
        }
        if (value != 0 && value < minNonZeroValue) {
            minNonZeroValue = value;
        }
    }

    private void resize(long newHighestTrackableValue) {
        establishSize(newHighestTrackableValue);
        int newLength = countsArrayLengthFor(bucketCount);
        if (newLength > counts.length) {
            counts = Arrays.copyOf(counts, newLength);
        }
    }

    public long getCountAtValue(long value) {
        int index = Math.min(Math.max(0, countsArrayIndex(value)), counts.length - 1);
        return counts[index];
    }

    public long getCountBetweenValues(long lowValue, long highValue) {
        int low = Math.max(0, countsArrayIndex(lowValue));
        int high = Math.min(countsArrayIndex(highValue), counts.length - 1);
        long c = 0;
        for (int i = low; i <= high; i++) {
            c += counts[i];
        }
        return c;
    }

    public long getTotalCount() {
        return totalCount;
    }

    public long getMaxValue() {
        return maxValue == 0 ? 0 : highestEquivalentValue(maxValue);
    }

    public long getMinValue() {
        if (totalCount == 0 || counts[0] > 0) {
            return 0;
        }
        return getMinNonZeroValue();
    }

    public long getMinNonZeroValue() {
        return minNonZeroValue == Long.MAX_VALUE ? Long.MAX_VALUE : lowestEquivalentValue(minNonZeroValue);
    }

    public double getMean() {
        if (totalCount == 0) {
            return 0.0;
        }
        double total = 0;
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] != 0) {
                total += medianEquivalentValue(valueFromIndex(i)) * (double) counts[i];
            }
        }
        return total / totalCount;
    }

    public double getStdDeviation() {
        if (totalCount == 0) {
            return 0.0;
        }
        double mean = getMean();
        double geometricDeviationTotal = 0.0;
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] != 0) {
                double dev = medianEquivalentValue(valueFromIndex(i)) - mean;
                geometricDeviationTotal += dev * dev * counts[i];
            }
        }
        return Math.sqrt(geometricDeviationTotal / totalCount);
    }

    public long getValueAtPercentile(double percentile) {
        double requestedPercentile = Math.min(Math.max(Math.nextAfter(percentile, Double.NEGATIVE_INFINITY), 0.0), 100.0);
        long countAtPercentile = (long) Math.ceil((requestedPercentile / 100.0) * totalCount);
        countAtPercentile = Math.max(countAtPercentile, 1);
        long totalToCurrentIndex = 0;
        for (int i = 0; i < counts.length; i++) {
            totalToCurrentIndex += counts[i];
            if (totalToCurrentIndex >= countAtPercentile) {
                long valueAtIndex = valueFromIndex(i);
                return (percentile == 0.0) ? lowestEquivalentValue(valueAtIndex) : highestEquivalentValue(valueAtIndex);
            }
        }
        return 0;
    }

    public double getPercentileAtOrBelowValue(long value) {
        if (totalCount == 0) {
            return 100.0;
        }
        if (value < 0) {
            return 0.0;
        }
        int targetIndex = Math.min(countsArrayIndex(value), counts.length - 1);
        long totalToCurrentIndex = 0;
        for (int i = 0; i <= targetIndex; i++) {
            totalToCurrentIndex += counts[i];
        }
        return (100.0 * totalToCurrentIndex) / totalCount;
    }

    public interface BucketConsumer {
        void accept(long lowestEquivalentValue, long highestEquivalentValue, long count);
    }

    public void forEachRecordedValue(BucketConsumer consumer) {
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] != 0) {
                long v = valueFromIndex(i);
                consumer.accept(lowestEquivalentValue(v), highestEquivalentValue(v), counts[i]);
            }
        }
    }

    public void add(HdrHistogram other) {
        if (other == this) {
            HdrHistogram copy = other.copy();
            add(copy);
            return;
        }
        boolean sameLayout = other.unitMagnitude == unitMagnitude && other.subBucketHalfCountMagnitude == subBucketHalfCountMagnitude;
        if (other.totalCount == 0) {
            return;
        }
        long otherMax = other.maxValue;
        if (countsArrayIndex(otherMax) >= counts.length) {
            if (!autoResize) {
                throw new IndexOutOfBoundsException("other histogram contains values outside of the covered range " + highestTrackableValue);
            }
            resize(otherMax);
        }
        if (sameLayout) {
            for (int i = 0; i < other.counts.length; i++) {
                long c = other.counts[i];
                if (c != 0) {
                    counts[i] += c;
                }
            }
            totalCount += other.totalCount;
            if (other.maxValue > maxValue) {
                maxValue = other.maxValue;
            }
            if (other.minNonZeroValue < minNonZeroValue) {
                minNonZeroValue = other.minNonZeroValue;
            }
        } else {
            for (int i = 0; i < other.counts.length; i++) {
                long c = other.counts[i];
                if (c != 0) {
                    recordValueWithCount(other.valueFromIndex(i), c);
                }
            }
            if (other.maxValue > maxValue) {
                maxValue = other.maxValue;
            }
            if (other.minNonZeroValue < minNonZeroValue) {
                minNonZeroValue = other.minNonZeroValue;
            }
        }
    }

    public HdrHistogram shiftedLeft(int shift) {
        if (shift < 0) {
            throw new IllegalArgumentException("shift must be >= 0");
        }
        HdrHistogram result = new HdrHistogram(lowestDiscernibleValue, Math.max(2 * lowestDiscernibleValue, 2), numberOfSignificantValueDigits, true);
        for (int i = 0; i < counts.length; i++) {
            long c = counts[i];
            if (c != 0) {
                result.recordValueWithCount(valueFromIndex(i) << shift, c);
            }
        }
        if (maxValue != 0) {
            result.maxValue = maxValue << shift;
        }
        if (minNonZeroValue != Long.MAX_VALUE) {
            result.minNonZeroValue = minNonZeroValue << shift;
        }
        return result;
    }

    public HdrHistogram shiftedRight(int shift) {
        if (shift < 0) {
            throw new IllegalArgumentException("shift must be >= 0");
        }
        HdrHistogram result = new HdrHistogram(lowestDiscernibleValue, Math.max(2 * lowestDiscernibleValue, 2), numberOfSignificantValueDigits, true);
        for (int i = 0; i < counts.length; i++) {
            long c = counts[i];
            if (c != 0) {
                result.recordValueWithCount(valueFromIndex(i) >>> shift, c);
            }
        }
        result.maxValue = maxValue >>> shift;
        if (minNonZeroValue != Long.MAX_VALUE) {
            long mn = minNonZeroValue >>> shift;
            result.minNonZeroValue = mn == 0 ? result.minNonZeroValue : Math.min(result.minNonZeroValue, mn);
        }
        return result;
    }

    public void reset() {
        Arrays.fill(counts, 0);
        totalCount = 0;
        maxValue = 0;
        minNonZeroValue = Long.MAX_VALUE;
    }

    public void writeTo(DataOutput out) throws IOException {
        out.writeByte(SERIAL_VERSION);
        out.writeByte(numberOfSignificantValueDigits);
        out.writeLong(lowestDiscernibleValue);
        out.writeLong(highestTrackableValue);
        out.writeBoolean(autoResize);
        out.writeLong(maxValue);
        out.writeLong(minNonZeroValue);
        int nonZero = 0;
        for (long c : counts) {
            if (c != 0) {
                nonZero++;
            }
        }
        out.writeInt(nonZero);
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] != 0) {
                out.writeInt(i);
                out.writeLong(counts[i]);
            }
        }
    }

    public static HdrHistogram readFrom(DataInput in) throws IOException {
        int version = in.readUnsignedByte();
        if (version != SERIAL_VERSION) {
            throw new IOException("unsupported HDR histogram serialization version: " + version);
        }
        int digits = in.readUnsignedByte();
        long lowest = in.readLong();
        long highest = in.readLong();
        boolean autoResize = in.readBoolean();
        long maxValue = in.readLong();
        long minNonZero = in.readLong();
        HdrHistogram h;
        try {
            h = new HdrHistogram(lowest, highest, digits, autoResize);
        } catch (IllegalArgumentException e) {
            throw new IOException("invalid HDR histogram header", e);
        }
        int nonZero = in.readInt();
        if (nonZero < 0) {
            throw new IOException("invalid entry count: " + nonZero);
        }
        for (int k = 0; k < nonZero; k++) {
            int index = in.readInt();
            long count = in.readLong();
            if (index < 0 || count < 0) {
                throw new IOException("invalid histogram entry");
            }
            if (index >= h.counts.length) {
                h.resize(h.valueFromIndex(index));
                if (index >= h.counts.length) {
                    throw new IOException("histogram entry index out of range: " + index);
                }
            }
            h.counts[index] += count;
            h.totalCount += count;
        }
        h.maxValue = maxValue;
        h.minNonZeroValue = minNonZero;
        return h;
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

    public static HdrHistogram fromBytes(byte[] bytes) {
        try {
            return readFrom(new DataInputStream(new ByteArrayInputStream(bytes)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
