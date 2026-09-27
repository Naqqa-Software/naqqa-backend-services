package com.naqqa.elasticsearch.index.engine;

import com.naqqa.elasticsearch.codec.Codec;
import com.naqqa.elasticsearch.codec.segment.SegmentCommitInfo;
import com.naqqa.elasticsearch.codec.segment.SegmentInfos;
import com.naqqa.elasticsearch.common.bytes.BytesReference;
import com.naqqa.elasticsearch.common.exception.VersionConflictEngineException;
import com.naqqa.elasticsearch.common.json.JsonObject;
import com.naqqa.elasticsearch.common.json.JsonValue;
import com.naqqa.elasticsearch.common.json.JsonWriter;
import com.naqqa.elasticsearch.index.engine.segment.SegmentMerger;
import com.naqqa.elasticsearch.index.engine.segment.SegmentReader;
import com.naqqa.elasticsearch.index.engine.segment.SegmentWriter;
import com.naqqa.elasticsearch.index.engine.segment.StoredDocCodec;
import com.naqqa.elasticsearch.index.engine.segment.TieredMergePolicy;
import com.naqqa.elasticsearch.index.mapper.MapperService;
import com.naqqa.elasticsearch.index.mapper.ParsedDocument;
import com.naqqa.elasticsearch.index.seqno.LocalCheckpointTracker;
import com.naqqa.elasticsearch.index.seqno.SequenceNumbers;
import com.naqqa.elasticsearch.index.translog.LiveVersionMap;
import com.naqqa.elasticsearch.index.translog.Operation;
import com.naqqa.elasticsearch.index.translog.Translog;
import com.naqqa.elasticsearch.index.translog.VersionType;
import com.naqqa.elasticsearch.index.translog.VersionValue;
import com.naqqa.elasticsearch.store.Directory;
import com.naqqa.elasticsearch.store.Lock;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class InternalEngine extends Engine {

    private final Directory directory;
    private final MapperService mapperService;
    private final Translog translog;
    private final LocalCheckpointTracker localCheckpointTracker;
    private final LiveVersionMap versionMap = new LiveVersionMap();
    private final DocBuffer docBuffer = new DocBuffer();
    private final Lock writeLock;
    private final TieredMergePolicy mergePolicy;
    private final AtomicLong segmentCounter;

    private final Object refreshMutex = new Object();
    private final Object viewLock = new Object();
    private final Object mergeMutex = new Object();

    private volatile List<SegmentReader> currentReaders;
    private volatile SegmentInfos lastCommit;
    private volatile long lastFlushCheckpoint;
    private volatile boolean closed;

    private final ExecutorService mergeExecutor;
    private final AtomicBoolean mergeScheduled = new AtomicBoolean(false);
    private ScheduledExecutorService refreshScheduler;

    private InternalEngine(EngineConfig config, Directory directory, Lock writeLock, SegmentInfos lastCommit,
                            List<SegmentReader> initialReaders, LocalCheckpointTracker tracker, Translog translog) {
        super(config);
        this.directory = directory;
        this.writeLock = writeLock;
        this.mapperService = config.mapperService();
        this.lastCommit = lastCommit;
        this.currentReaders = List.copyOf(initialReaders);
        this.localCheckpointTracker = tracker;
        this.translog = translog;
        this.lastFlushCheckpoint = tracker.getCheckpoint();
        this.mergePolicy = new TieredMergePolicy(config.maxMergeAtOnce(), config.segmentsPerTier(), config.maxDeletedPctAllowed());
        this.segmentCounter = new AtomicLong(scanMaxSegmentOrdinal(directory) + 1);
        this.mergeExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "engine-merge");
            t.setDaemon(true);
            return t;
        });
    }

    public static InternalEngine open(EngineConfig config) throws IOException {
        Directory directory = config.directory();
        Lock writeLock = null;
        try {
            writeLock = directory.obtainLock("write.lock");
        } catch (Exception ignored) {
        }
        SegmentInfos lastCommit = SegmentInfos.readLatestCommit(directory);
        List<SegmentReader> readers = new ArrayList<>();
        for (SegmentCommitInfo sci : lastCommit.segments()) {
            readers.add(SegmentReader.open(directory, sci));
        }
        long localCheckpoint = parseLongOr(lastCommit.userData().get("local_checkpoint"), SequenceNumbers.NO_OPS_PERFORMED);
        long maxSeqNo = parseLongOr(lastCommit.userData().get("max_seq_no"), SequenceNumbers.NO_OPS_PERFORMED);
        LocalCheckpointTracker tracker = new LocalCheckpointTracker(localCheckpoint, maxSeqNo);
        Translog translog = Translog.open(config.translogConfig().translogPath(), config.translogConfig());
        InternalEngine engine = new InternalEngine(config, directory, writeLock, lastCommit, readers, tracker, translog);
        engine.recoverFromTranslog();
        engine.startBackgroundRefresh();
        return engine;
    }

    private static long parseLongOr(String s, long def) {
        if (s == null) {
            return def;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static long scanMaxSegmentOrdinal(Directory dir) {
        long max = -1;
        try {
            String suffix = "." + Codec.SEGMENT_INFO_EXT;
            for (String f : dir.listAll()) {
                if (f.endsWith(suffix)) {
                    String base = f.substring(0, f.length() - suffix.length());
                    if (base.startsWith("_")) {
                        try {
                            long v = Long.parseLong(base.substring(1), 36);
                            if (v > max) {
                                max = v;
                            }
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return max;
    }

    private String nextSegmentName() {
        return "_" + Long.toString(segmentCounter.getAndIncrement(), 36);
    }

    private void startBackgroundRefresh() {
        long millis = engineConfig.refreshInterval().millis();
        if (millis <= 0) {
            return;
        }
        refreshScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "engine-refresh");
            t.setDaemon(true);
            return t;
        });
        refreshScheduler.scheduleWithFixedDelay(() -> {
            try {
                refresh("scheduled");
                maybeFlushBySize();
            } catch (Exception ignored) {
            }
        }, millis, millis, TimeUnit.MILLISECONDS);
    }

    private void recoverFromTranslog() throws IOException {
        long fromSeqNo = localCheckpointTracker.getCheckpoint() + 1;
        try (Translog.Snapshot snapshot = translog.newSnapshot(fromSeqNo)) {
            Operation op;
            while ((op = snapshot.next()) != null) {
                switch (op.opType()) {
                    case INDEX -> {
                        Operation.Index idx = (Operation.Index) op;
                        if (mapperService.documentMapper() != null) {
                            JsonObject src = (JsonObject) JsonValue.parse(idx.source().toBytesArray());
                            ParsedDocument parsed = mapperService.documentMapper().parse(idx.id(), idx.routing(), src);
                            docBuffer.put(idx.id(), BufferedDoc.indexed(idx.id(), idx.seqNo(), idx.primaryTerm(), idx.version(), parsed));
                            versionMap.putUnderLock(idx.id(), VersionValue.index(idx.version(), idx.seqNo(), idx.primaryTerm(), null));
                        }
                    }
                    case DELETE -> {
                        Operation.Delete del = (Operation.Delete) op;
                        docBuffer.put(del.id(), BufferedDoc.tombstone(del.id(), del.seqNo(), del.primaryTerm(), del.version()));
                        versionMap.putUnderLock(del.id(), VersionValue.tombstone(del.version(), del.seqNo(), del.primaryTerm(), null));
                    }
                    case NO_OP -> {
                    }
                }
                localCheckpointTracker.markSeqNoAsProcessed(op.seqNo());
            }
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new EngineException("engine is closed");
        }
    }

    private record CurrentDocInfo(boolean exists, long version, long seqNo, long primaryTerm) {
        static final CurrentDocInfo NOT_FOUND = new CurrentDocInfo(false, Versions.NOT_FOUND,
            SequenceNumbers.UNASSIGNED_SEQ_NO, SequenceNumbers.UNASSIGNED_PRIMARY_TERM);
    }

    private CurrentDocInfo lookupCurrent(String id) throws IOException {
        BufferedDoc bd = docBuffer.get(id);
        if (bd != null) {
            return new CurrentDocInfo(!bd.deleted(), bd.version(), bd.seqNo(), bd.primaryTerm());
        }
        VersionValue vv = versionMap.getUnderLock(id);
        if (vv != null) {
            return new CurrentDocInfo(!vv.isDelete(), vv.version(), vv.seqNo(), vv.term());
        }
        return lookupInReaders(id);
    }

    private CurrentDocInfo lookupInReaders(String id) throws IOException {
        List<SegmentReader> view = currentReaders;
        for (SegmentReader r : view) {
            r.incRef();
            try {
                Integer docId = r.findLiveDocForId(SegmentWriter.ID_FIELD, id);
                if (docId != null) {
                    StoredDocCodec.Decoded d = StoredDocCodec.decode(r.document(docId));
                    return new CurrentDocInfo(true, d.version(), d.seqNo(), d.primaryTerm());
                }
            } finally {
                r.decRef();
            }
        }
        return CurrentDocInfo.NOT_FOUND;
    }

    @Override
    public IndexResult index(IndexOperation op) throws IOException {
        ensureOpen();
        if (mapperService.documentMapper() == null) {
            return IndexResult.failure(new EngineException("no mapping defined for index [" + mapperService.indexName() + "]"));
        }
        String id = op.id();
        CurrentDocInfo cur = lookupCurrent(id);
        if (op.ifSeqNo() != SequenceNumbers.UNASSIGNED_SEQ_NO) {
            if (cur.seqNo() != op.ifSeqNo() || cur.primaryTerm() != op.ifPrimaryTerm()) {
                return IndexResult.failure(new VersionConflictEngineException(mapperService.indexName(), id, cur.seqNo(), op.ifSeqNo()));
            }
        }
        long newVersion;
        if (op.versionType() == VersionType.INTERNAL) {
            if (op.version() != Versions.MATCH_ANY && cur.exists() && op.version() != cur.version()) {
                return IndexResult.failure(new VersionConflictEngineException(mapperService.indexName(), id, cur.version(), op.version()));
            }
            newVersion = cur.exists() ? cur.version() + 1 : 1;
        } else {
            if (cur.exists() && op.versionType() == VersionType.EXTERNAL && op.version() <= cur.version()) {
                return IndexResult.failure(new VersionConflictEngineException(mapperService.indexName(), id, cur.version(), op.version()));
            }
            if (cur.exists() && op.versionType() == VersionType.EXTERNAL_GTE && op.version() < cur.version()) {
                return IndexResult.failure(new VersionConflictEngineException(mapperService.indexName(), id, cur.version(), op.version()));
            }
            newVersion = op.version();
        }
        long seqNo = localCheckpointTracker.generateSeqNo();
        long primaryTerm = engineConfig.primaryTerm();
        ParsedDocument parsed = mapperService.parse(id, op.routing(), op.source());
        byte[] sourceBytes = JsonWriter.toJsonBytes(JsonValue.wrap(op.source()), false);
        Translog.Location loc = translog.add(new Operation.Index(id, seqNo, primaryTerm, newVersion, BytesReference.of(sourceBytes), op.routing()));
        versionMap.putUnderLock(id, VersionValue.index(newVersion, seqNo, primaryTerm, loc));
        docBuffer.put(id, BufferedDoc.indexed(id, seqNo, primaryTerm, newVersion, parsed));
        localCheckpointTracker.markSeqNoAsProcessed(seqNo);
        maybeFlushBySize();
        return IndexResult.success(seqNo, primaryTerm, newVersion, !cur.exists());
    }

    @Override
    public DeleteResult delete(DeleteOperation op) throws IOException {
        ensureOpen();
        String id = op.id();
        CurrentDocInfo cur = lookupCurrent(id);
        if (op.ifSeqNo() != SequenceNumbers.UNASSIGNED_SEQ_NO) {
            if (cur.seqNo() != op.ifSeqNo() || cur.primaryTerm() != op.ifPrimaryTerm()) {
                return DeleteResult.failure(new VersionConflictEngineException(mapperService.indexName(), id, cur.seqNo(), op.ifSeqNo()));
            }
        }
        long newVersion;
        if (op.versionType() == VersionType.INTERNAL) {
            if (op.version() != Versions.MATCH_ANY && cur.exists() && op.version() != cur.version()) {
                return DeleteResult.failure(new VersionConflictEngineException(mapperService.indexName(), id, cur.version(), op.version()));
            }
            newVersion = cur.exists() ? cur.version() + 1 : 1;
        } else {
            newVersion = op.version();
        }
        long seqNo = localCheckpointTracker.generateSeqNo();
        long primaryTerm = engineConfig.primaryTerm();
        Translog.Location loc = translog.add(new Operation.Delete(id, seqNo, primaryTerm, newVersion));
        versionMap.putUnderLock(id, VersionValue.tombstone(newVersion, seqNo, primaryTerm, loc));
        docBuffer.put(id, BufferedDoc.tombstone(id, seqNo, primaryTerm, newVersion));
        localCheckpointTracker.markSeqNoAsProcessed(seqNo);
        maybeFlushBySize();
        return DeleteResult.success(seqNo, primaryTerm, newVersion, cur.exists());
    }

    @Override
    public NoOpResult noOp(NoOpOperation op) throws IOException {
        ensureOpen();
        long seqNo = localCheckpointTracker.generateSeqNo();
        long primaryTerm = engineConfig.primaryTerm();
        translog.add(new Operation.NoOp(seqNo, primaryTerm, op.reason()));
        localCheckpointTracker.markSeqNoAsProcessed(seqNo);
        return NoOpResult.success(seqNo, primaryTerm);
    }

    @Override
    public GetResult get(String id) throws IOException {
        ensureOpen();
        BufferedDoc bd = docBuffer.get(id);
        if (bd != null) {
            if (bd.deleted()) {
                return GetResult.notFound(id);
            }
            byte[] sourceBytes = SegmentWriter.extractSourceBytes(bd.doc());
            return GetResult.found(id, bd.version(), bd.seqNo(), bd.primaryTerm(), BytesReference.of(sourceBytes));
        }
        VersionValue vv = versionMap.getUnderLock(id);
        if (vv != null && vv.isDelete()) {
            return GetResult.notFound(id);
        }
        List<SegmentReader> view = currentReaders;
        for (SegmentReader r : view) {
            r.incRef();
            try {
                Integer docId = r.findLiveDocForId(SegmentWriter.ID_FIELD, id);
                if (docId != null) {
                    StoredDocCodec.Decoded d = StoredDocCodec.decode(r.document(docId));
                    return GetResult.found(id, d.version(), d.seqNo(), d.primaryTerm(), BytesReference.of(d.source()));
                }
            } finally {
                r.decRef();
            }
        }
        return GetResult.notFound(id);
    }

    @Override
    public EngineSearcher acquireSearcher() throws IOException {
        ensureOpen();
        List<SegmentReader> view;
        synchronized (viewLock) {
            view = currentReaders;
            for (SegmentReader r : view) {
                r.incRef();
            }
        }
        return new EngineSearcher(view, () -> {
            for (SegmentReader r : view) {
                try {
                    r.decRef();
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
        });
    }

    @Override
    public RefreshResult refresh(String source) throws IOException {
        ensureOpen();
        synchronized (refreshMutex) {
            Map<String, BufferedDoc> toFlush = docBuffer.beforeRefresh();
            if (toFlush.isEmpty()) {
                docBuffer.afterRefresh();
                return new RefreshResult(false, currentReaders.size());
            }
            List<BufferedDoc> liveDocsToWrite = new ArrayList<>();
            for (BufferedDoc bd : toFlush.values()) {
                if (!bd.deleted()) {
                    liveDocsToWrite.add(bd);
                }
            }
            SegmentReader newReader = null;
            if (!liveDocsToWrite.isEmpty()) {
                String segName = nextSegmentName();
                com.naqqa.elasticsearch.codec.segment.SegmentInfo info = SegmentWriter.write(directory, segName, liveDocsToWrite);
                newReader = SegmentReader.open(directory, new SegmentCommitInfo(segName, 0, 0));
            }
            synchronized (viewLock) {
                List<SegmentReader> oldView = currentReaders;
                for (SegmentReader r : oldView) {
                    for (String id : toFlush.keySet()) {
                        Integer docId = r.findLiveDocForId(SegmentWriter.ID_FIELD, id);
                        if (docId != null) {
                            r.markDeleted(docId);
                        }
                    }
                }
                List<SegmentReader> newView = new ArrayList<>(oldView);
                if (newReader != null) {
                    newView.add(newReader);
                }
                currentReaders = List.copyOf(newView);
            }
            docBuffer.afterRefresh();
            RefreshResult result = new RefreshResult(true, currentReaders.size());
            scheduleMaybeMerge();
            return result;
        }
    }

    private void maybeFlushBySize() throws IOException {
        if (translog.sizeInBytes() >= engineConfig.flushThresholdSize().getBytes()) {
            flush(false);
        }
    }

    @Override
    public FlushResult flush(boolean force) throws IOException {
        ensureOpen();
        synchronized (refreshMutex) {
            RefreshResult r = refresh("flush");
            long checkpoint = localCheckpointTracker.getCheckpoint();
            List<SegmentReader> view;
            synchronized (viewLock) {
                view = currentReaders;
            }
            boolean anyDirty = false;
            for (SegmentReader sr : view) {
                if (sr.isDirty()) {
                    anyDirty = true;
                    break;
                }
            }
            boolean needsFlush = force || r.refreshed() || checkpoint != lastFlushCheckpoint || anyDirty;
            if (!needsFlush) {
                return new FlushResult(false, lastCommit.generation());
            }
            List<SegmentCommitInfo> commitInfos = new ArrayList<>();
            for (SegmentReader sr : view) {
                commitInfos.add(sr.persistIfDirty(directory));
            }
            Map<String, String> userData = new LinkedHashMap<>();
            userData.put("local_checkpoint", Long.toString(checkpoint));
            userData.put("max_seq_no", Long.toString(localCheckpointTracker.getMaxSeqNo()));
            userData.put("translog_uuid", translog.getTranslogUUID());
            SegmentInfos newInfos = new SegmentInfos(lastCommit.generation(), commitInfos, userData);
            lastCommit = newInfos.commit(directory);
            lastFlushCheckpoint = checkpoint;
            translog.rollGeneration();
            translog.trimUnreferencedReaders(lastFlushCheckpoint + 1);
            return new FlushResult(true, lastCommit.generation());
        }
    }

    private void scheduleMaybeMerge() {
        if (closed) {
            return;
        }
        if (mergeScheduled.compareAndSet(false, true)) {
            mergeExecutor.submit(() -> {
                try {
                    maybeMergeOnce();
                } catch (Exception ignored) {
                } finally {
                    mergeScheduled.set(false);
                }
            });
        }
    }

    private void maybeMergeOnce() throws IOException {
        synchronized (mergeMutex) {
            List<SegmentReader> viewSnapshot;
            synchronized (viewLock) {
                viewSnapshot = currentReaders;
            }
            List<SegmentReader> batch = mergePolicy.findMerge(viewSnapshot);
            if (batch == null) {
                return;
            }
            for (SegmentReader r : batch) {
                r.incRef();
            }
            try {
                doMerge(batch);
            } finally {
                for (SegmentReader r : batch) {
                    r.decRef();
                }
            }
        }
    }

    @Override
    public MergeResult forceMerge(int maxSegments) throws IOException {
        ensureOpen();
        refresh("force_merge");
        int before = currentReaders.size();
        synchronized (mergeMutex) {
            while (true) {
                List<SegmentReader> viewSnapshot;
                synchronized (viewLock) {
                    viewSnapshot = currentReaders;
                }
                List<SegmentReader> batch = mergePolicy.pickForceMergeBatch(viewSnapshot, maxSegments);
                if (batch == null) {
                    break;
                }
                for (SegmentReader r : batch) {
                    r.incRef();
                }
                try {
                    doMerge(batch);
                } finally {
                    for (SegmentReader r : batch) {
                        r.decRef();
                    }
                }
            }
        }
        int after;
        synchronized (viewLock) {
            after = currentReaders.size();
        }
        return new MergeResult(before, after);
    }

    private void doMerge(List<SegmentReader> candidates) throws IOException {
        String segName = nextSegmentName();
        SegmentMerger.merge(directory, segName, candidates);
        SegmentReader merged = SegmentReader.open(directory, new SegmentCommitInfo(segName, 0, 0));
        synchronized (viewLock) {
            List<SegmentReader> newView = new ArrayList<>(currentReaders);
            newView.removeAll(candidates);
            newView.add(merged);
            currentReaders = List.copyOf(newView);
            for (SegmentReader old : candidates) {
                old.scheduleDeletionWhenUnreferenced(() -> deleteSegmentFiles(old));
            }
        }
    }

    private void deleteSegmentFiles(SegmentReader r) {
        for (String f : r.allFiles()) {
            try {
                directory.deleteFile(f);
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public EngineStats stats() {
        List<SegmentReader> view = currentReaders;
        int numDocs = 0;
        int numDeleted = 0;
        for (SegmentReader r : view) {
            numDocs += r.numDocs();
            numDeleted += (r.maxDoc() - r.numDocs());
        }
        long translogOps = 0;
        try (Translog.Snapshot snapshot = translog.newSnapshot()) {
            translogOps = snapshot.totalOperations();
        }
        return new EngineStats(numDocs, numDeleted, view.size(), translog.sizeInBytes(), translogOps,
            localCheckpointTracker.getMaxSeqNo(), localCheckpointTracker.getCheckpoint());
    }

    @Override
    public void flushAndClose() throws IOException {
        if (closed) {
            return;
        }
        flush(true);
        close();
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        if (refreshScheduler != null) {
            refreshScheduler.shutdownNow();
        }
        mergeExecutor.shutdown();
        try {
            mergeExecutor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (viewLock) {
            for (SegmentReader r : currentReaders) {
                try {
                    r.decRef();
                } catch (IOException ignored) {
                }
            }
            currentReaders = List.of();
        }
        translog.close();
        if (writeLock != null) {
            writeLock.close();
        }
    }
}
