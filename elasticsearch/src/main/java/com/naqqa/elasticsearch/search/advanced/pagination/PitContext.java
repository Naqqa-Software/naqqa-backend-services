package com.naqqa.elasticsearch.search.advanced.pagination;

import com.naqqa.elasticsearch.index.engine.EngineSearcher;
import com.naqqa.elasticsearch.search.advanced.common.SegmentReaderLeafAdapter;
import com.naqqa.elasticsearch.search.execution.IndexSearcher;

import java.io.IOException;

public final class PitContext {

    private final String id;
    private final EngineSearcher engineSearcher;
    private final IndexSearcher indexSearcher;
    private final long createdAtNanos;
    private volatile long expiresAtNanos;

    PitContext(String id, EngineSearcher engineSearcher, long keepAliveMillis) {
        this.id = id;
        this.engineSearcher = engineSearcher;
        this.indexSearcher = new IndexSearcher(SegmentReaderLeafAdapter.wrap(engineSearcher.leaves()));
        this.createdAtNanos = System.nanoTime();
        this.expiresAtNanos = createdAtNanos + keepAliveMillis * 1_000_000L;
    }

    public String id() {
        return id;
    }

    public IndexSearcher searcher() {
        return indexSearcher;
    }

    public EngineSearcher engineSearcher() {
        return engineSearcher;
    }

    public boolean isExpired() {
        return System.nanoTime() > expiresAtNanos;
    }

    void extend(long keepAliveMillis) {
        expiresAtNanos = System.nanoTime() + keepAliveMillis * 1_000_000L;
    }

    void close() throws IOException {
        engineSearcher.close();
    }
}
