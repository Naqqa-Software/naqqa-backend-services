package com.naqqa.elasticsearch.index.engine;

import java.io.Closeable;
import java.io.IOException;

public abstract class Engine implements Closeable {

    protected final EngineConfig engineConfig;

    protected Engine(EngineConfig engineConfig) {
        this.engineConfig = engineConfig;
    }

    public EngineConfig config() {
        return engineConfig;
    }

    public abstract IndexResult index(IndexOperation op) throws IOException;

    public abstract DeleteResult delete(DeleteOperation op) throws IOException;

    public abstract NoOpResult noOp(NoOpOperation op) throws IOException;

    public abstract GetResult get(String id) throws IOException;

    public abstract EngineSearcher acquireSearcher() throws IOException;

    public abstract RefreshResult refresh(String source) throws IOException;

    public abstract FlushResult flush(boolean force) throws IOException;

    public abstract MergeResult forceMerge(int maxSegments) throws IOException;

    public abstract EngineStats stats();

    public abstract void flushAndClose() throws IOException;

    @Override
    public abstract void close() throws IOException;
}
