package com.naqqa.elasticsearch.index.recovery;

import com.naqqa.elasticsearch.common.bytes.BytesReference;
import com.naqqa.elasticsearch.common.json.JsonValue;
import com.naqqa.elasticsearch.common.json.JsonWriter;
import com.naqqa.elasticsearch.common.threadpool.ThreadPool;
import com.naqqa.elasticsearch.index.engine.GetResult;
import com.naqqa.elasticsearch.index.engine.IndexOperation;
import com.naqqa.elasticsearch.index.engine.IndexResult;
import com.naqqa.elasticsearch.index.mapper.MapperService;
import com.naqqa.elasticsearch.index.shard.IndexShard;
import com.naqqa.elasticsearch.index.translog.Operation;
import com.naqqa.elasticsearch.index.translog.TranslogConfig;
import com.naqqa.elasticsearch.index.translog.VersionType;
import com.naqqa.elasticsearch.store.Directory;
import com.naqqa.elasticsearch.store.FSDirectory;
import com.naqqa.elasticsearch.test.Test;
import com.naqqa.elasticsearch.transport.Connection;
import com.naqqa.elasticsearch.transport.ConnectionProfile;
import com.naqqa.elasticsearch.transport.TransportService;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertFalse;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class ExplicitSeqNoRecoveryTest {

    private static Operation.Index wireOp(String id, long seqNo, long term, long version, String title) {
        byte[] bytes = JsonWriter.toJsonBytes(JsonValue.wrap(Map.of("title", title)), false);
        return new Operation.Index(id, seqNo, term, version, BytesReference.of(bytes), null);
    }

    private static IndexOperation replicaOp(String id, String title, long version) {
        return IndexOperation.of(id, Map.of("title", title)).withVersion(version, VersionType.EXTERNAL);
    }

    private static final class ScriptedOpsSource implements LiveOpsSource {
        final NavigableMap<Long, Operation> ops = new TreeMap<>();
        final List<Long> hidden = new ArrayList<>();
        final AtomicInteger calls = new AtomicInteger();
        volatile IntConsumer onCall = n -> { };

        synchronized void record(Operation op) {
            ops.put(op.seqNo(), op);
        }

        @Override
        public Iterator<Operation> opsSince(long fromSeqNo) {
            onCall.accept(calls.incrementAndGet());
            List<Operation> out = new ArrayList<>();
            synchronized (this) {
                for (Operation op : ops.tailMap(fromSeqNo, true).values()) {
                    if (!hidden.contains(op.seqNo())) {
                        out.add(op);
                    }
                }
            }
            return out.iterator();
        }
    }

    private static final class Env implements AutoCloseable {
        final Path sourcePath = RecoveryTestSupport.newTempShardPath("explicit-recovery-source");
        final Path targetPath = RecoveryTestSupport.newTempShardPath("explicit-recovery-target");
        final MapperService targetMapper = RecoveryTestSupport.newMapperService();
        final TranslogConfig sourceTranslogConfig = RecoveryTestSupport.newTranslogConfig(sourcePath);
        final TranslogConfig targetTranslogConfig = RecoveryTestSupport.newTranslogConfig(targetPath);
        final IndexShard sourceShard;
        final Directory sourceDirectory;
        final ScriptedOpsSource opsSource = new ScriptedOpsSource();
        final ThreadPool poolA = new ThreadPool();
        final ThreadPool poolB = new ThreadPool();
        final TransportService sourceTransport;
        final TransportService targetTransport;
        final Connection connectionToSource;

        Env() throws IOException {
            sourceShard = RecoveryTestSupport.openShard(sourcePath, RecoveryTestSupport.newMapperService(), sourceTranslogConfig);
            sourceDirectory = new FSDirectory(sourcePath.resolve("index"));
            sourceTransport = new TransportService("source", new InetSocketAddress("127.0.0.1", 0), poolA);
            targetTransport = new TransportService("target", new InetSocketAddress("127.0.0.1", 0), poolB);
            sourceTransport.start();
            targetTransport.start();
            RecoverySourceHandler handler = new RecoverySourceHandler(sourceShard, sourceDirectory, opsSource,
                RecoveryThrottler.unthrottled(), new RetentionLeaseTracker());
            handler.registerHandlers(sourceTransport);
            connectionToSource = targetTransport.connectToNode(sourceTransport.localNode(), ConnectionProfile.buildDefault());
        }

        IndexShard recover(RecoveryState state) throws IOException {
            return RecoveryTarget.recover(targetPath, targetMapper, targetTranslogConfig, targetTransport, connectionToSource, state,
                RecoveryTarget.DEFAULT_CHUNK_SIZE, 100, 40, 10L);
        }

        RecoveryState newState() {
            return new RecoveryState(RecoveryState.Type.PEER, sourceTransport.localNode(), targetTransport.localNode());
        }

        @Override
        public void close() throws IOException {
            sourceShard.close();
            sourceDirectory.close();
            connectionToSource.close();
            sourceTransport.close();
            targetTransport.close();
            poolA.close();
            poolB.close();
        }
    }

    private static void assertSameSeqNoHistory(IndexShard expected, IndexShard actual) {
        assertEquals(expected.localCheckpoint(), actual.localCheckpoint());
        assertEquals(expected.maxSeqNo(), actual.maxSeqNo());
        for (long s = 0; s <= expected.maxSeqNo() + 2; s++) {
            assertEquals(expected.hasProcessedSeqNo(s), actual.hasProcessedSeqNo(s), "processed mismatch at seq_no " + s);
        }
    }

    @Test
    public void outOfOrderDeliveryWithGapThatIsLaterFilledMatchesSourceCheckpoint() throws Exception {
        try (Env env = new Env()) {
            for (int i = 0; i < 6; i++) {
                String id = "doc-" + i;
                IndexResult r = env.sourceShard.index(id, Map.of("title", "v" + i));
                env.opsSource.record(wireOp(id, r.seqNo(), r.primaryTerm(), r.version(), "v" + i));
            }
            env.opsSource.hidden.add(3L);
            env.opsSource.onCall = n -> {
                if (n >= 3) {
                    env.opsSource.hidden.clear();
                }
            };

            RecoveryState state = env.newState();
            IndexShard target = env.recover(state);
            try {
                assertEquals(5L, env.sourceShard.localCheckpoint());
                assertSameSeqNoHistory(env.sourceShard, target);
                assertEquals(6L, state.translogOpsRecovered());
                for (int i = 0; i < 6; i++) {
                    GetResult expected = env.sourceShard.get("doc-" + i);
                    GetResult actual = target.get("doc-" + i);
                    assertTrue(actual.exists(), "expected doc-" + i + " on target");
                    assertEquals(expected.seqNo(), actual.seqNo());
                    assertEquals(expected.primaryTerm(), actual.primaryTerm());
                    assertEquals(expected.version(), actual.version());
                }
            } finally {
                target.close();
            }
        }
    }

    @Test
    public void sourceSideGapFilledDuringRecoveryIsReplayedAtItsExactSeqNo() throws Exception {
        try (Env env = new Env()) {
            long[] seqNos = {0, 1, 3, 4};
            for (long s : seqNos) {
                String id = "doc-" + s;
                assertTrue(env.sourceShard.indexAtSeqNo(replicaOp(id, "v" + s, 1), s, 1).success());
                env.opsSource.record(wireOp(id, s, 1, 1, "v" + s));
            }
            assertEquals(1L, env.sourceShard.localCheckpoint());
            assertEquals(4L, env.sourceShard.maxSeqNo());
            env.opsSource.onCall = n -> {
                if (n == 2) {
                    try {
                        assertTrue(env.sourceShard.indexAtSeqNo(replicaOp("doc-2", "v2", 1), 2, 1).success());
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    env.opsSource.record(wireOp("doc-2", 2, 1, 1, "v2"));
                }
            };

            RecoveryState state = env.newState();
            IndexShard target = env.recover(state);
            try {
                assertEquals(4L, env.sourceShard.localCheckpoint());
                assertSameSeqNoHistory(env.sourceShard, target);
                assertEquals(5L, state.translogOpsRecovered());
                for (long s = 0; s <= 4; s++) {
                    GetResult actual = target.get("doc-" + s);
                    assertTrue(actual.exists(), "expected doc-" + s + " on target");
                    assertEquals(s, actual.seqNo());
                }
            } finally {
                target.close();
            }
        }
    }

    @Test
    public void replayOfAlreadyProcessedOpIsIdempotent() throws Exception {
        Path path = RecoveryTestSupport.newTempShardPath("explicit-recovery-dup");
        IndexShard shard = RecoveryTestSupport.openShard(path, RecoveryTestSupport.newMapperService());
        try {
            assertTrue(RecoveryTarget.replay(shard, wireOp("a", 0, 1, 1, "first")));
            long opsBefore = shard.stats().translogNumOps();
            assertFalse(RecoveryTarget.replay(shard, wireOp("a", 0, 1, 1, "second")));
            assertFalse(RecoveryTarget.replay(shard, new Operation.Delete("a", 0, 1, 2)));
            assertFalse(RecoveryTarget.replay(shard, new Operation.NoOp(0, 1, "dup")));
            assertEquals(opsBefore, shard.stats().translogNumOps());
            assertEquals(0L, shard.localCheckpoint());
            assertEquals(0L, shard.maxSeqNo());
            GetResult got = shard.get("a");
            assertTrue(got.exists());
            assertEquals(1L, got.version());
            assertEquals("first", ((Map<?, ?>) JsonValue.parse(got.source().toBytesArray()).toJava()).get("title"));

            assertTrue(RecoveryTarget.replay(shard, new Operation.NoOp(2, 1, "ahead")));
            assertEquals(0L, shard.localCheckpoint());
            assertTrue(RecoveryTarget.replay(shard, new Operation.Delete("a", 1, 1, 2)));
            assertEquals(2L, shard.localCheckpoint());
            assertFalse(shard.get("a").exists());
        } finally {
            shard.close();
        }
    }
}
