package com.naqqa.elasticsearch.common.util;

public interface ObjectArray<T> extends AutoCloseable {

    long size();

    T get(long index);

    T set(long index, T value);

    @Override
    void close();
}
