package com.naqqa.elasticsearch.common.util;

public interface LongArray extends AutoCloseable {

    long size();

    long get(long index);

    long set(long index, long value);

    void fill(long fromIndex, long toIndex, long value);

    @Override
    void close();
}
