package com.naqqa.elasticsearch.common.util;

public interface ByteArray extends AutoCloseable {

    long size();

    byte get(long index);

    byte set(long index, byte value);

    void fill(long fromIndex, long toIndex, byte value);

    @Override
    void close();
}
