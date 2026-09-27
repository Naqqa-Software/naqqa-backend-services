package com.naqqa.elasticsearch.index.recovery;

import com.naqqa.elasticsearch.common.bytes.BytesReference;
import com.naqqa.elasticsearch.common.json.JsonValue;
import com.naqqa.elasticsearch.common.json.JsonWriter;
import com.naqqa.elasticsearch.common.threadpool.ThreadPool;
import com.naqqa.elasticsearch.index.engine.IndexResult;
import com.naqqa.elasticsearch.index.mapper.MapperService;
import com.naqqa.elasticsearch.index.shard.IndexShard;
import com.naqqa.elasticsearch.index.translog.Operation;
import com.naqqa.elasticsearch.index.translog.TranslogConfig;
import com.naqqa.elasticsearch.store.Directory;
import com.naqqa.elasticsearch.store.FSDirectory;
import com.naqqa.elasticsearch.test.Test;
import com.naqqa.elasticsearch.transport.Connection;
import com.naqqa.elasticsearch.transport.ConnectionProfile;
import com.naqqa.elasticsearch.transport.TransportService;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Map;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class ConcurrentPhase2RecoveryTest {

    private static Operation.Index toWireOp(IndexResult result, String id, String routing, Map<String, Object> source) {
        byte[] bytes = JsonWriter.toJsonBytes(JsonValue.wrap(source), false);
        return new Operation.Index(id, result.seqNo(), result.primaryTerm(), result.version(), BytesReference.of(bytes), routing);
    }

    @Test
    public void phase2CatchesUpWithWritesArrivingConcurrentlyOnSource() throws Exception {
        Path sourcePath = RecoveryTestSupport.newTempShardPath("recovery-concurrent-source");
        Path targetPath = RecoveryTestSupport.newTempShardPath("recovery-concurrent-target");
        MapperService sourceMapper = RecoveryTestSupport.newMapperService();
        MapperService targetMapper = RecoveryTestSupport.newMapperService();
        TranslogConfig sourceTranslogConfig = RecoveryTestSupport.newTranslogConfig(sourcePath);
        TranslogConfig targetTranslogConfig = RecoveryTestSupport.newTranslogConfig(targetPath);

        IndexShard sourceShard = RecoveryTestSupport.openShard(sourcePath, sourceMapper, sourceTranslogConfig);
        Directory sourceDirectory = new FSDirectory(sourcePath.resolve("index"));
        BufferingLiveOpsSource opsSource = new BufferingLiveOpsSource();
        RecoveryThrottler throttler = RecoveryThrottler.unthrottled();
        RetentionLeaseTracker leaseTracker = new RetentionLeaseTracker();

        for (int i = 0; i < 3; i++) {
            String id = "doc-" + i;
            Map<String, Object> source = Map.of("title", "v" + i);
            IndexResult result = sourceShard.index(id, source);
            opsSource.record(toWireOp(result, id, null, source));
        }

        ThreadPool poolA = new ThreadPool();
        ThreadPool poolB = new ThreadPool();
        TransportService sourceTransport = new TransportService("source", new InetSocketAddress("127.0.0.1", 0), poolA);
        TransportService targetTransport = new TransportService("target", new InetSocketAddress("127.0.0.1", 0), poolB);
        sourceTransport.start();
        targetTransport.start();

        RecoverySourceHandler sourceHandler = new RecoverySourceHandler(sourceShard, sourceDirectory, opsSource, throttler, leaseTracker);
        sourceHandler.registerHandlers(sourceTransport);
        Connection connectionToSource = targetTransport.connectToNode(sourceTransport.localNode(), ConnectionProfile.buildDefault());

        Thread writer = new Thread(() -> {
            try {
                Thread.sleep(120);
                for (int i = 3; i < 6; i++) {
                    String id = "doc-" + i;
                    Map<String, Object> source = Map.of("title", "v" + i);
                    IndexResult result = sourceShard.index(id, source);
                    opsSource.record(toWireOp(result, id, null, source));
                    Thread.sleep(80);
                }
            } catch (Exception ignored) {
            }
        });
        writer.start();

        RecoveryState state = new RecoveryState(RecoveryState.Type.PEER, sourceTransport.localNode(), targetTransport.localNode());
        IndexShard targetShard = RecoveryTarget.recover(targetPath, targetMapper, targetTranslogConfig, targetTransport, connectionToSource, state,
            RecoveryTarget.DEFAULT_CHUNK_SIZE, 100, 40, 30L);
        writer.join();

        try {
            assertEquals(0, state.filesRecovered());
            for (int i = 0; i < 6; i++) {
                assertTrue(targetShard.get("doc-" + i).exists(), "expected doc-" + i + " to have been replayed onto target");
            }
            assertEquals(6L, state.translogOpsRecovered());
        } finally {
            targetShard.close();
            sourceShard.close();
            sourceDirectory.close();
            connectionToSource.close();
            sourceTransport.close();
            targetTransport.close();
            poolA.close();
            poolB.close();
        }
    }
}
