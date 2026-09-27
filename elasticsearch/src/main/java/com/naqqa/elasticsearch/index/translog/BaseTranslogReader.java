package com.naqqa.elasticsearch.index.translog;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.List;

abstract class BaseTranslogReader {

    protected final FileChannel channel;
    protected final long generation;
    protected final int headerLength;

    BaseTranslogReader(FileChannel channel, long generation, int headerLength) {
        this.channel = channel;
        this.generation = generation;
        this.headerLength = headerLength;
    }

    long generation() {
        return generation;
    }

    protected abstract long endOffset();

    List<Operation> readOperations(long fromPosition) throws IOException {
        return TranslogScanner.scan(channel, fromPosition, endOffset()).operations();
    }

    List<Operation> readAllOperations() throws IOException {
        return readOperations(headerLength);
    }
}
