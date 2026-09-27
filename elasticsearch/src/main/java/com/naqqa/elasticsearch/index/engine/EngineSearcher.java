package com.naqqa.elasticsearch.index.engine;

import com.naqqa.elasticsearch.index.engine.segment.SegmentReader;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class EngineSearcher implements Closeable {

    private final List<SegmentReader> leaves;
    private final Runnable onClose;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public EngineSearcher(List<SegmentReader> leaves, Runnable onClose) {
        this.leaves = leaves;
        this.onClose = onClose;
    }

    public List<SegmentReader> leaves() {
        return leaves;
    }

    public int numDocs() {
        int total = 0;
        for (SegmentReader r : leaves) {
            total += r.numDocs();
        }
        return total;
    }

    public int maxDoc() {
        int total = 0;
        for (SegmentReader r : leaves) {
            total += r.maxDoc();
        }
        return total;
    }

    @Override
    public void close() throws IOException {
        if (closed.compareAndSet(false, true)) {
            onClose.run();
        }
    }
}
