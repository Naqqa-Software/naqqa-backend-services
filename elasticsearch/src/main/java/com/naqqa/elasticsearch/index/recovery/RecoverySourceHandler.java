package com.naqqa.elasticsearch.index.recovery;

import com.naqqa.elasticsearch.index.engine.EngineStats;
import com.naqqa.elasticsearch.index.shard.IndexShard;
import com.naqqa.elasticsearch.index.translog.Operation;
import com.naqqa.elasticsearch.index.translog.Releasable;
import com.naqqa.elasticsearch.store.Directory;
import com.naqqa.elasticsearch.store.IOContext;
import com.naqqa.elasticsearch.store.IndexInput;
import com.naqqa.elasticsearch.transport.TransportChannel;
import com.naqqa.elasticsearch.transport.TransportService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class RecoverySourceHandler {

    private final IndexShard shard;
    private final Directory directory;
    private final LiveOpsSource opsSource;
    private final RecoveryThrottler throttler;
    private final RetentionLeaseTracker leaseTracker;
    private final AtomicLong recoveryIdGenerator = new AtomicLong();
    private final Map<Long, String> activeLeaseIds = new ConcurrentHashMap<>();
    private final Map<Long, Releasable> activeCommitRefs = new ConcurrentHashMap<>();

    public RecoverySourceHandler(IndexShard shard, Directory directory, LiveOpsSource opsSource,
                                  RecoveryThrottler throttler, RetentionLeaseTracker leaseTracker) {
        this.shard = shard;
        this.directory = directory;
        this.opsSource = opsSource;
        this.throttler = throttler;
        this.leaseTracker = leaseTracker;
    }

    public void registerHandlers(TransportService transportService) {
        transportService.registerRequestHandler(RecoveryActions.START, RecoveryStartRequest::new, this::handleStart);
        transportService.registerRequestHandler(RecoveryActions.FILE_CHUNK, RecoveryFileChunkRequest::new, this::handleFileChunk);
        transportService.registerRequestHandler(RecoveryActions.TRANSLOG_OPS, RecoveryTranslogRequest::new, this::handleTranslogOps);
        transportService.registerRequestHandler(RecoveryActions.FINISH, RecoveryFinishRequest::new, this::handleFinish);
    }

    private void handleFinish(RecoveryFinishRequest request, TransportChannel channel) throws IOException {
        finishRecovery(request.recoveryId());
        channel.sendResponse(RecoveryAckResponse.INSTANCE);
    }

    private void handleStart(RecoveryStartRequest request, TransportChannel channel) throws IOException {
        Releasable commitRef = shard.acquireLastCommitRef();
        try {
            List<StoreFileMetadata> sourceFiles = StoreFiles.latestCommitFiles(directory);
            Map<String, StoreFileMetadata> sourceByName = StoreFiles.byName(sourceFiles);
            Map<String, StoreFileMetadata> targetByName = StoreFiles.byName(request.targetFiles());

            List<StoreFileMetadata> filesToFetch = new ArrayList<>();
            for (StoreFileMetadata sourceFile : sourceFiles) {
                StoreFileMetadata targetFile = targetByName.get(sourceFile.name());
                if (!sourceFile.sameAs(targetFile)) {
                    filesToFetch.add(sourceFile);
                }
            }
            List<String> filesToDelete = new ArrayList<>();
            for (String targetName : targetByName.keySet()) {
                if (!sourceByName.containsKey(targetName)) {
                    filesToDelete.add(targetName);
                }
            }

            long recoveryId = recoveryIdGenerator.incrementAndGet();
            String leaseId = "recovery-" + recoveryId;
            activeLeaseIds.put(recoveryId, leaseId);
            activeCommitRefs.put(recoveryId, commitRef);
            commitRef = null;
            leaseTracker.addOrRenew(leaseId, request.startingSeqNo(), "peer-recovery");

            EngineStats stats = shard.stats();
            boolean opsBasedRecovery = filesToFetch.isEmpty() && request.startingSeqNo() <= stats.localCheckpoint() + 1;

            channel.sendResponse(new RecoveryStartResponse(
                recoveryId, filesToFetch, filesToDelete, stats.localCheckpoint(), stats.maxSeqNo(), opsBasedRecovery));
        } finally {
            if (commitRef != null) {
                commitRef.close();
            }
        }
    }

    private void handleFileChunk(RecoveryFileChunkRequest request, TransportChannel channel) throws IOException {
        byte[] data = new byte[request.length()];
        try (IndexInput in = directory.openInput(request.fileName(), IOContext.READ)) {
            in.seek(request.position());
            in.readBytes(data, 0, request.length());
        }
        throttler.throttle(data.length);
        channel.sendResponse(new RecoveryFileChunkResponse(data));
    }

    private void handleTranslogOps(RecoveryTranslogRequest request, TransportChannel channel) throws IOException {
        Iterator<Operation> it = opsSource.opsSince(request.fromSeqNo());
        List<Operation> ops = new ArrayList<>();
        while (it.hasNext() && ops.size() < request.maxOps()) {
            ops.add(it.next());
        }
        long checkpoint = shard.stats().localCheckpoint();
        channel.sendResponse(new RecoveryTranslogResponse(ops, checkpoint));
    }

    public void finishRecovery(long recoveryId) {
        String leaseId = activeLeaseIds.remove(recoveryId);
        if (leaseId != null) {
            leaseTracker.remove(leaseId);
        }
        Releasable commitRef = activeCommitRefs.remove(recoveryId);
        if (commitRef != null) {
            commitRef.close();
        }
    }

    Set<Long> activeRecoveries() {
        return activeLeaseIds.keySet();
    }
}
