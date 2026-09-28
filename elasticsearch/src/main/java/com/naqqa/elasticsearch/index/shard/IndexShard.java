package com.naqqa.elasticsearch.index.shard;

import com.naqqa.elasticsearch.index.engine.DeleteOperation;
import com.naqqa.elasticsearch.index.engine.DeleteResult;
import com.naqqa.elasticsearch.index.engine.Engine;
import com.naqqa.elasticsearch.index.engine.EngineConfig;
import com.naqqa.elasticsearch.index.engine.EngineSearcher;
import com.naqqa.elasticsearch.index.engine.EngineStats;
import com.naqqa.elasticsearch.index.engine.FlushResult;
import com.naqqa.elasticsearch.index.engine.GetResult;
import com.naqqa.elasticsearch.index.engine.IndexOperation;
import com.naqqa.elasticsearch.index.engine.IndexResult;
import com.naqqa.elasticsearch.index.engine.InternalEngine;
import com.naqqa.elasticsearch.index.engine.MergeResult;
import com.naqqa.elasticsearch.index.engine.NoOpOperation;
import com.naqqa.elasticsearch.index.engine.NoOpResult;
import com.naqqa.elasticsearch.index.engine.RefreshResult;
import com.naqqa.elasticsearch.index.mapper.MapperService;
import com.naqqa.elasticsearch.index.translog.Releasable;
import com.naqqa.elasticsearch.index.translog.TranslogConfig;
import com.naqqa.elasticsearch.store.Directory;
import com.naqqa.elasticsearch.store.FSDirectory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

public final class IndexShard implements Closeable {

    private final Engine engine;
    private final MapperService mapperService;

    private IndexShard(Engine engine, MapperService mapperService) {
        this.engine = engine;
        this.mapperService = mapperService;
    }

    public static IndexShard open(Path shardPath, MapperService mapperService) throws IOException {
        TranslogConfig translogConfig = TranslogConfig.defaultConfig(shardPath.resolve("translog"));
        Directory directory = new FSDirectory(shardPath.resolve("index"));
        EngineConfig config = EngineConfig.defaultConfig(shardPath, directory, mapperService, translogConfig);
        return open(config, mapperService);
    }

    public static IndexShard open(Path shardPath, MapperService mapperService, TranslogConfig translogConfig) throws IOException {
        Directory directory = new FSDirectory(shardPath.resolve("index"));
        EngineConfig config = EngineConfig.defaultConfig(shardPath, directory, mapperService, translogConfig);
        return open(config, mapperService);
    }

    public static IndexShard open(EngineConfig config, MapperService mapperService) throws IOException {
        Engine engine = InternalEngine.open(config);
        return new IndexShard(engine, mapperService);
    }

    public MapperService mapperService() {
        return mapperService;
    }

    public Engine engine() {
        return engine;
    }

    public IndexResult index(String id, Map<String, Object> source) throws IOException {
        return engine.index(IndexOperation.of(id, source));
    }

    public IndexResult index(String id, String routing, Map<String, Object> source) throws IOException {
        return engine.index(IndexOperation.of(id, routing, source));
    }

    public IndexResult index(IndexOperation op) throws IOException {
        return engine.index(op);
    }

    public IndexResult index(IndexOperation op, boolean fsyncTranslog) throws IOException {
        return engine.index(op, fsyncTranslog);
    }

    public DeleteResult delete(String id) throws IOException {
        return engine.delete(DeleteOperation.of(id));
    }

    public DeleteResult delete(DeleteOperation op) throws IOException {
        return engine.delete(op);
    }

    public DeleteResult delete(DeleteOperation op, boolean fsyncTranslog) throws IOException {
        return engine.delete(op, fsyncTranslog);
    }

    public void syncTranslog() throws IOException {
        engine.syncTranslog();
    }

    public NoOpResult noOp(String reason) throws IOException {
        return engine.noOp(new NoOpOperation(reason));
    }

    public IndexResult indexAtSeqNo(IndexOperation op, long seqNo, long primaryTerm) throws IOException {
        return engine.indexAtSeqNo(op, seqNo, primaryTerm);
    }

    public DeleteResult deleteAtSeqNo(DeleteOperation op, long seqNo, long primaryTerm) throws IOException {
        return engine.deleteAtSeqNo(op, seqNo, primaryTerm);
    }

    public NoOpResult noOpAtSeqNo(String reason, long seqNo, long primaryTerm) throws IOException {
        return engine.noOpAtSeqNo(new NoOpOperation(reason), seqNo, primaryTerm);
    }

    public boolean hasProcessedSeqNo(long seqNo) {
        return engine.hasProcessedSeqNo(seqNo);
    }

    public long localCheckpoint() {
        return engine.localCheckpoint();
    }

    public long maxSeqNo() {
        return engine.maxSeqNo();
    }

    public GetResult get(String id) throws IOException {
        return engine.get(id);
    }

    public EngineSearcher acquireSearcher() throws IOException {
        return engine.acquireSearcher();
    }

    public RefreshResult refresh() throws IOException {
        return engine.refresh("api");
    }

    public FlushResult flush(boolean force) throws IOException {
        return engine.flush(force);
    }

    public MergeResult forceMerge(int maxSegments) throws IOException {
        return engine.forceMerge(maxSegments);
    }

    public EngineStats stats() {
        return engine.stats();
    }

    public Releasable acquireLastCommitRef() {
        return engine.acquireLastCommitRef();
    }

    public com.naqqa.elasticsearch.index.translog.Translog.Snapshot newTranslogSnapshot(long fromSeqNo) throws IOException {
        return engine.newTranslogSnapshot(fromSeqNo);
    }

    public int docCount() {
        return engine.stats().numDocs();
    }

    public int segmentCount() {
        return engine.stats().segmentCount();
    }

    public void flushAndClose() throws IOException {
        engine.flushAndClose();
    }

    @Override
    public void close() throws IOException {
        engine.close();
    }
}
