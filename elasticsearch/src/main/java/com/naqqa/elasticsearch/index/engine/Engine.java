package com.naqqa.elasticsearch.index.engine;

import com.naqqa.elasticsearch.index.translog.Releasable;

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

    public IndexResult index(IndexOperation op, boolean fsyncTranslog) throws IOException {
        return index(op);
    }

    public abstract DeleteResult delete(DeleteOperation op) throws IOException;

    public DeleteResult delete(DeleteOperation op, boolean fsyncTranslog) throws IOException {
        return delete(op);
    }

    public void syncTranslog() throws IOException {
    }

    public abstract NoOpResult noOp(NoOpOperation op) throws IOException;

    public abstract IndexResult indexAtSeqNo(IndexOperation op, long seqNo, long primaryTerm) throws IOException;

    public abstract DeleteResult deleteAtSeqNo(DeleteOperation op, long seqNo, long primaryTerm) throws IOException;

    public abstract NoOpResult noOpAtSeqNo(NoOpOperation op, long seqNo, long primaryTerm) throws IOException;

    public abstract boolean hasProcessedSeqNo(long seqNo);

    public abstract long localCheckpoint();

    public abstract long maxSeqNo();

    public abstract GetResult get(String id) throws IOException;

    public abstract EngineSearcher acquireSearcher() throws IOException;

    public abstract RefreshResult refresh(String source) throws IOException;

    public abstract FlushResult flush(boolean force) throws IOException;

    public abstract MergeResult forceMerge(int maxSegments) throws IOException;

    public abstract EngineStats stats();

    public Releasable acquireLastCommitRef() {
        return () -> {
        };
    }

    public com.naqqa.elasticsearch.index.translog.Translog.Snapshot newTranslogSnapshot(long fromSeqNo) throws IOException {
        throw new UnsupportedOperationException("engine [" + getClass().getName() + "] does not support translog snapshots");
    }

    public abstract void flushAndClose() throws IOException;

    @Override
    public abstract void close() throws IOException;
}
