package com.naqqa.elasticsearch.store;

import java.io.Closeable;
import java.io.IOException;

public abstract class IndexOutput extends DataOutput implements Closeable {

    private final String resourceDescription;
    private final String name;

    protected IndexOutput(String resourceDescription, String name) {
        if (resourceDescription == null) {
            throw new IllegalArgumentException("resourceDescription must not be null");
        }
        this.resourceDescription = resourceDescription;
        this.name = name;
    }

    public String getName() {
        return name;
    }

    @Override
    public abstract void close() throws IOException;

    public abstract long getFilePointer();

    public abstract long getChecksum() throws IOException;

    public void alignFilePointer(int alignmentBytes) throws IOException {
        long pos = getFilePointer();
        long aligned = (pos + alignmentBytes - 1) / alignmentBytes * alignmentBytes;
        for (long i = pos; i < aligned; i++) {
            writeByte((byte) 0);
        }
    }

    @Override
    public String toString() {
        return resourceDescription;
    }
}
