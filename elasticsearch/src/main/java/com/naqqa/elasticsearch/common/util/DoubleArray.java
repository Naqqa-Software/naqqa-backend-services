package com.naqqa.elasticsearch.common.util;

public interface DoubleArray extends AutoCloseable {

    long size();

    double get(long index);

    double set(long index, double value);

    void fill(long fromIndex, long toIndex, double value);

    @Override
    void close();
}
