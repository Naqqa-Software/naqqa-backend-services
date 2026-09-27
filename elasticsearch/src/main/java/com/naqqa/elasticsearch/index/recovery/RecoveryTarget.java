package com.naqqa.elasticsearch.index.recovery;

import com.naqqa.elasticsearch.common.io.stream.Writeable;
import com.naqqa.elasticsearch.common.json.JsonValue;
import com.naqqa.elasticsearch.index.engine.DeleteOperation;
import com.naqqa.elasticsearch.index.engine.DeleteResult;
import com.naqqa.elasticsearch.index.engine.IndexOperation;
import com.naqqa.elasticsearch.index.engine.IndexResult;
import com.naqqa.elasticsearch.index.engine.NoOpResult;
import com.naqqa.elasticsearch.index.mapper.MapperService;
import com.naqqa.elasticsearch.index.seqno.SequenceNumbers;
import com.naqqa.elasticsearch.index.shard.IndexShard;
import com.naqqa.elasticsearch.index.translog.Operation;
import com.naqqa.elasticsearch.index.translog.TranslogConfig;
import com.naqqa.elasticsearch.index.translog.VersionType;
import com.naqqa.elasticsearch.codec.segment.SegmentInfos;
import com.naqqa.elasticsearch.store.Directory;
import com.naqqa.elasticsearch.store.FSDirectory;
import com.naqqa.elasticsearch.store.IOContext;
import com.naqqa.elasticsearch.store.IndexOutput;
import com.naqqa.elasticsearch.transport.Connection;
import com.naqqa.elasticsearch.transport.TransportException;
import com.naqqa.elasticsearch.transport.TransportRequest;
import com.naqqa.elasticsearch.transport.TransportRequestOptions;
import com.naqqa.elasticsearch.transport.TransportResponse;
import com.naqqa.elasticsearch.transport.TransportResponseHandler;
import com.naqqa.elasticsearch.transport.TransportService;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class RecoveryTarget {

    public static final int DEFAULT_CHUNK_SIZE = 64 * 1024;
    public static final int DEFAULT_MAX_OPS_PER_ROUND = 1000;
    public static final int DEFAULT_MAX_ROUNDS = 20;
    public static final long DEFAULT_POLL_DELAY_MILLIS = 50L;
    private static final int IDLE_STREAK_TO_FINALIZE = 3;
    private static final long REQUEST_TIMEOUT_MILLIS = 30_000L;

    private RecoveryTarget() {
    }

    public static IndexShard recover(Path shardPath, MapperService mapperService, TranslogConfig translogConfig,
                                      TransportService transportService, Connection sourceConnection,
                                      RecoveryState state) throws IOException {
        return recover(shardPath, mapperService, translogConfig, transportService, sourceConnection, state,
            DEFAULT_CHUNK_SIZE, DEFAULT_MAX_OPS_PER_ROUND, DEFAULT_MAX_ROUNDS, DEFAULT_POLL_DELAY_MILLIS);
    }

    public static IndexShard recover(Path shardPath, MapperService mapperService, TranslogConfig translogConfig,
                                      TransportService transportService, Connection sourceConnection, RecoveryState state,
                                      int chunkSize, int maxOpsPerRound, int maxRounds, long pollDelayMillis) throws IOException {
        state.start();
        state.setStage(RecoveryState.Stage.INIT);

        Path indexPath = shardPath.resolve("index");
        long recoveryId;
        RecoveryStartResponse startResponse;
        Directory targetDirectory = new FSDirectory(indexPath);
        try {
            List<StoreFileMetadata> targetFiles = StoreFiles.latestCommitFiles(targetDirectory);
            long targetCheckpoint = readLocalCheckpoint(targetDirectory);
            long startingSeqNo = targetCheckpoint + 1;

            startResponse = sendSync(transportService, sourceConnection, RecoveryActions.START,
                new RecoveryStartRequest(startingSeqNo, targetFiles), RecoveryStartResponse::new);
            recoveryId = startResponse.recoveryId();

            state.setStage(RecoveryState.Stage.INDEX);
            state.setFilesTotal(startResponse.filesToFetch().size());
            long totalBytes = 0;
            for (StoreFileMetadata file : startResponse.filesToFetch()) {
                totalBytes += file.length();
            }
            state.setBytesTotal(totalBytes);

            for (StoreFileMetadata file : startResponse.filesToFetch()) {
                fetchFile(transportService, sourceConnection, recoveryId, targetDirectory, file, chunkSize, state);
            }
            for (String stale : startResponse.filesToDelete()) {
                if (targetDirectory.fileExists(stale)) {
                    targetDirectory.deleteFile(stale);
                }
            }
        } finally {
            targetDirectory.close();
        }

        IndexShard shard = IndexShard.open(shardPath, mapperService, translogConfig);

        try {
            state.setStage(RecoveryState.Stage.TRANSLOG);
            state.setTranslogOpsTotal(Math.max(0, startResponse.sourceMaxSeqNo() - startResponse.sourceCheckpoint()));

            long fromSeqNo = shard.localCheckpoint() + 1;
            int round = 0;
            boolean caughtUp = false;
            int idleStreak = 0;
            while (round < maxRounds) {
                RecoveryTranslogResponse translogResponse = sendSync(transportService, sourceConnection, RecoveryActions.TRANSLOG_OPS,
                    new RecoveryTranslogRequest(recoveryId, fromSeqNo, maxOpsPerRound), RecoveryTranslogResponse::new);
                int applied = 0;
                for (Operation op : translogResponse.operations()) {
                    if (replay(shard, op)) {
                        applied++;
                        state.addTranslogOpsRecovered(1);
                    }
                    fromSeqNo = Math.max(fromSeqNo, op.seqNo() + 1);
                }
                if (translogResponse.operations().isEmpty() && shard.localCheckpoint() < shard.maxSeqNo()) {
                    fromSeqNo = shard.localCheckpoint() + 1;
                }
                if (applied > 0) {
                    idleStreak = 0;
                } else if (shard.localCheckpoint() >= translogResponse.sourceCheckpoint()) {
                    idleStreak++;
                    if (idleStreak >= IDLE_STREAK_TO_FINALIZE) {
                        caughtUp = true;
                        break;
                    }
                } else {
                    idleStreak = 0;
                }
                round++;
                if (applied == 0) {
                    try {
                        Thread.sleep(pollDelayMillis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            if (!caughtUp) {
                throw new IOException("peer recovery failed to catch up on translog ops within [" + maxRounds + "] rounds");
            }
        } catch (IOException e) {
            shard.close();
            throw e;
        }

        sendSync(transportService, sourceConnection, RecoveryActions.FINISH,
            new RecoveryFinishRequest(recoveryId), RecoveryAckResponse::new);

        state.done();
        return shard;
    }

    private static void fetchFile(TransportService transportService, Connection sourceConnection, long recoveryId,
                                   Directory targetDirectory, StoreFileMetadata file, int chunkSize, RecoveryState state) throws IOException {
        if (targetDirectory.fileExists(file.name())) {
            targetDirectory.deleteFile(file.name());
        }
        String tempName;
        try (IndexOutput out = targetDirectory.createTempOutput("recovery", "tmp", IOContext.DEFAULT)) {
            tempName = out.getName();
            long position = 0;
            while (position < file.length()) {
                int len = (int) Math.min(chunkSize, file.length() - position);
                RecoveryFileChunkResponse chunkResponse = sendSync(transportService, sourceConnection, RecoveryActions.FILE_CHUNK,
                    new RecoveryFileChunkRequest(recoveryId, file.name(), position, len), RecoveryFileChunkResponse::new);
                out.writeBytes(chunkResponse.data(), 0, chunkResponse.data().length);
                position += chunkResponse.data().length;
                state.addBytesRecovered(chunkResponse.data().length);
            }
        }
        targetDirectory.sync(List.of(tempName));
        targetDirectory.rename(tempName, file.name());
        targetDirectory.syncMetaData();
        StoreFiles.verifyChecksum(targetDirectory, file);
        state.incrementFilesRecovered();
    }

    static boolean replay(IndexShard shard, Operation op) throws IOException {
        if (shard.hasProcessedSeqNo(op.seqNo())) {
            return false;
        }
        switch (op.opType()) {
            case INDEX -> {
                Operation.Index idx = (Operation.Index) op;
                @SuppressWarnings("unchecked")
                Map<String, Object> source = (Map<String, Object>) JsonValue.parse(idx.source().toBytesArray()).toJava();
                IndexOperation indexOp = new IndexOperation(idx.id(), idx.routing(), source, idx.version(), VersionType.EXTERNAL_GTE,
                    SequenceNumbers.UNASSIGNED_SEQ_NO, SequenceNumbers.UNASSIGNED_PRIMARY_TERM);
                IndexResult result = shard.indexAtSeqNo(indexOp, idx.seqNo(), idx.primaryTerm());
                if (!result.success()) {
                    throw new IOException("failed to replay index op for id [" + idx.id() + "] at seq_no [" + idx.seqNo() + "]",
                        result.failure());
                }
            }
            case DELETE -> {
                Operation.Delete del = (Operation.Delete) op;
                DeleteOperation deleteOp = new DeleteOperation(del.id(), del.version(), VersionType.EXTERNAL_GTE,
                    SequenceNumbers.UNASSIGNED_SEQ_NO, SequenceNumbers.UNASSIGNED_PRIMARY_TERM);
                DeleteResult result = shard.deleteAtSeqNo(deleteOp, del.seqNo(), del.primaryTerm());
                if (!result.success()) {
                    throw new IOException("failed to replay delete op for id [" + del.id() + "] at seq_no [" + del.seqNo() + "]",
                        result.failure());
                }
            }
            case NO_OP -> {
                Operation.NoOp noOp = (Operation.NoOp) op;
                NoOpResult result = shard.noOpAtSeqNo(noOp.reason(), noOp.seqNo(), noOp.primaryTerm());
                if (!result.success()) {
                    throw new IOException("failed to replay no-op at seq_no [" + noOp.seqNo() + "]", result.failure());
                }
            }
        }
        return true;
    }

    private static long readLocalCheckpoint(Directory directory) throws IOException {
        SegmentInfos infos = SegmentInfos.readLatestCommit(directory);
        String raw = infos.userData().get("local_checkpoint");
        if (raw == null) {
            return SequenceNumbers.NO_OPS_PERFORMED;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return SequenceNumbers.NO_OPS_PERFORMED;
        }
    }

    private static <T extends TransportResponse> T sendSync(TransportService transportService, Connection connection,
                                                              String action, TransportRequest request,
                                                              Writeable.Reader<T> reader) throws IOException {
        CompletableFuture<T> future = new CompletableFuture<>();
        transportService.sendRequest(connection, action, request, TransportRequestOptions.of().withTimeout(REQUEST_TIMEOUT_MILLIS),
            new TransportResponseHandler<T>() {
                @Override
                public void handleResponse(T response) {
                    future.complete(response);
                }

                @Override
                public void handleException(TransportException exp) {
                    future.completeExceptionally(exp);
                }

                @Override
                public Writeable.Reader<T> reader() {
                    return reader;
                }
            });
        try {
            return future.get(REQUEST_TIMEOUT_MILLIS + 10_000L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for recovery action [" + action + "]", e);
        } catch (ExecutionException e) {
            throw new IOException("recovery action [" + action + "] failed", e.getCause());
        } catch (TimeoutException e) {
            throw new IOException("recovery action [" + action + "] timed out", e);
        }
    }
}
