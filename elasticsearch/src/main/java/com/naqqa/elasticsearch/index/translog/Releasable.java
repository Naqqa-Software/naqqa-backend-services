package com.naqqa.elasticsearch.index.translog;

public interface Releasable extends AutoCloseable {

    @Override
    void close();
}
