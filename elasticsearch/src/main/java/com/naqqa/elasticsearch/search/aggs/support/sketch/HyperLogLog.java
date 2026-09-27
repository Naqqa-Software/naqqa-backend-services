package com.naqqa.elasticsearch.search.aggs.support.sketch;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public final class HyperLogLog {

    private final HyperLogLogPlusPlus sketch;

    public HyperLogLog() {
        this(HyperLogLogPlusPlus.DEFAULT_PRECISION);
    }

    public HyperLogLog(int precision) {
        this.sketch = new HyperLogLogPlusPlus(precision, 1);
    }

    private HyperLogLog(HyperLogLogPlusPlus sketch) {
        this.sketch = sketch;
    }

    public static HyperLogLog withPrecisionThreshold(long precisionThreshold) {
        return new HyperLogLog(HyperLogLogPlusPlus.precisionFromThreshold(precisionThreshold));
    }

    public int precision() {
        return sketch.precision();
    }

    public void addHash(long hash) {
        sketch.collect(0, hash);
    }

    public void addLong(long value) {
        sketch.collect(0, MurmurHash3.hash64(value));
    }

    public void addDouble(double value) {
        sketch.collect(0, MurmurHash3.hash64(value));
    }

    public void addString(String value) {
        sketch.collect(0, MurmurHash3.hash64(value));
    }

    public void addBytes(byte[] value) {
        sketch.collect(0, MurmurHash3.hash64(value));
    }

    public long cardinality() {
        return sketch.cardinality(0);
    }

    public boolean isLinearCounting() {
        return sketch.isLinearCounting(0);
    }

    public void merge(HyperLogLog other) {
        sketch.merge(0, other.sketch, 0);
    }

    public HyperLogLogPlusPlus sketch() {
        return sketch;
    }

    public void writeTo(DataOutput out) throws IOException {
        sketch.writeTo(0, out);
    }

    public static HyperLogLog readFrom(DataInput in) throws IOException {
        return new HyperLogLog(HyperLogLogPlusPlus.readFrom(in));
    }

    public byte[] toBytes() {
        return sketch.toBytes(0);
    }

    public static HyperLogLog fromBytes(byte[] bytes) {
        return new HyperLogLog(HyperLogLogPlusPlus.fromBytes(bytes));
    }

    public boolean sameState(HyperLogLog other) {
        return sketch.equals(0, other.sketch, 0);
    }
}
