package com.naqqa.elasticsearch.store;

import java.io.Closeable;
import java.io.IOException;

public abstract class Lock implements Closeable {

    @Override
    public abstract void close() throws IOException;

    public abstract void ensureValid() throws IOException;
}
