package com.naqqa.elasticsearch.common.util;

public interface IntArray extends AutoCloseable {

    long size();

    int get(long index);

    int set(long index, int value);

    void fill(long fromIndex, long toIndex, int value);

    @Override
    void close();
}
