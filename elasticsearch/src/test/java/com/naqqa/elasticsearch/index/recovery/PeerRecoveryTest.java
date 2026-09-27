package com.naqqa.elasticsearch.index.recovery;

import com.naqqa.elasticsearch.common.json.JsonValue;
import com.naqqa.elasticsearch.common.threadpool.ThreadPool;
import com.naqqa.elasticsearch.index.engine.GetResult;
import com.naqqa.elasticsearch.index.mapper.MapperService;
import com.naqqa.elasticsearch.index.shard.IndexShard;
import com.naqqa.elasticsearch.index.translog.TranslogConfig;
import com.naqqa.elasticsearch.store.Directory;
import com.naqqa.elasticsearch.store.FSDirectory;
import com.naqqa.elasticsearch.test.Test;
import com.naqqa.elasticsearch.transport.Connection;
import com.naqqa.elasticsearch.transport.ConnectionProfile;
import com.naqqa.elasticsearch.transport.TransportService;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class PeerRecoveryTest {

    private static final class Env implements AutoCloseable {
        final Path sourcePath;
        final Path targetPath;
        final MapperService sourceMapper;
        final MapperService targetMapper;
        final TranslogConfig sourceTranslogConfig;
        final TranslogConfig targetTranslogConfig;
        final Directory sourceDirectory;
        final RecoveryThrottler throttler;
        final RetentionLeaseTracker leaseTracker;
        final ThreadPool poolA = new ThreadPool();
        final ThreadPool poolB = new ThreadPool();
        final TransportService sourceTransport;
        final TransportService targetTransport;
        final Connection connectionToSource;

        IndexShard sourceShard;

        Env() throws IOException {
            sourcePath = RecoveryTestSupport.newTempShardPath("recovery-source");
            targetPath = RecoveryTestSupport.newTempShardPath("recovery-target");
            sourceMapper = RecoveryTestSupport.newMapperService();
            targetMapper = RecoveryTestSupport.newMapperService();
            sourceTranslogConfig = RecoveryTestSupport.newTranslogConfig(sourcePath);
            targetTranslogConfig = RecoveryTestSupport.newTranslogConfig(targetPath);
            sourceShard = RecoveryTestSupport.openShard(sourcePath, sourceMapper, sourceTranslogConfig);
            sourceDirectory = new FSDirectory(sourcePath.resolve("index"));
            throttler = RecoveryThrottler.unthrottled();
            leaseTracker = new RetentionLeaseTracker();

            sourceTransport = new TransportService("source", new InetSocketAddress("127.0.0.1", 0), poolA);
            targetTransport = new TransportService("target", new InetSocketAddress("127.0.0.1", 0), poolB);
            sourceTransport.start();
            targetTransport.start();

            LiveOpsSource opsSource = new TranslogOpsSource(sourcePath.resolve("translog"), sourceTranslogConfig);
            RecoverySourceHandler sourceHandler = new RecoverySourceHandler(sourceShard, sourceDirectory, opsSource, throttler, leaseTracker);
            sourceHandler.registerHandlers(sourceTransport);

            connectionToSource = targetTransport.connectToNode(sourceTransport.localNode(), ConnectionProfile.buildDefault());
        }

        RecoveryState newState() {
            return new RecoveryState(RecoveryState.Type.PEER, sourceTransport.localNode(), targetTransport.localNode());
        }

        IndexShard runRecovery() throws IOException {
            return RecoveryTarget.recover(targetPath, targetMapper, targetTranslogConfig, targetTransport, connectionToSource, newState());
        }

        IndexShard runRecovery(RecoveryState state) throws IOException {
            return RecoveryTarget.recover(targetPath, targetMapper, targetTranslogConfig, targetTransport, connectionToSource, state);
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

    private static Map<String, Object> doc(String title) {
        return Map.of("title", title);
    }

    private static Map<String, Object> sourceAsMap(GetResult result) {
        return (Map<String, Object>) JsonValue.parse(result.source().toBytesArray()).toJava();
    }

    private static void copyDirectory(Path from, Path to) throws IOException {
        Files.createDirectories(to);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(from)) {
            for (Path p : stream) {
                Files.copy(p, to.resolve(p.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    @Test
    public void fullPeerRecoveryAcrossMultipleSegmentsProducesIdenticalDocSet() throws Exception {
        try (Env env = new Env()) {
            for (int i = 0; i < 5; i++) {
                env.sourceShard.index("doc-" + i, doc("v" + i));
            }
            env.sourceShard.flush(true);
            for (int i = 5; i < 10; i++) {
                env.sourceShard.index("doc-" + i, doc("v" + i));
            }
            env.sourceShard.flush(true);
            assertTrue(env.sourceShard.segmentCount() >= 2, "expected at least 2 segments on source");

            IndexShard targetShard = env.runRecovery();
            try {
                assertEquals(env.sourceShard.docCount(), targetShard.docCount());
                for (int i = 0; i < 10; i++) {
                    GetResult expected = env.sourceShard.get("doc-" + i);
                    GetResult actual = targetShard.get("doc-" + i);
                    assertTrue(actual.exists(), "expected doc-" + i + " to exist on target");
                    assertEquals(expected.version(), actual.version());
                    assertEquals(sourceAsMap(expected), sourceAsMap(actual));
                }
            } finally {
                targetShard.close();
            }
        }
    }

    @Test
    public void incrementalPeerRecoverySkipsIdenticalFiles() throws Exception {
        try (Env env = new Env()) {
            for (int i = 0; i < 5; i++) {
                env.sourceShard.index("doc-" + i, doc("v" + i));
            }
            env.sourceShard.flush(true);
            for (int i = 5; i < 10; i++) {
                env.sourceShard.index("doc-" + i, doc("v" + i));
            }
            env.sourceShard.flush(true);

            IndexShard targetShard = env.runRecovery();
            targetShard.close();

            Set<String> filesBefore = new HashSet<>();
            for (StoreFileMetadata f : StoreFiles.latestCommitFiles(env.sourceDirectory)) {
                filesBefore.add(f.name());
            }

            for (int i = 10; i < 12; i++) {
                env.sourceShard.index("doc-" + i, doc("v" + i));
            }
            env.sourceShard.flush(true);

            List<StoreFileMetadata> filesAfter = StoreFiles.latestCommitFiles(env.sourceDirectory);
            int expectedNewFiles = 0;
            for (StoreFileMetadata f : filesAfter) {
                if (!filesBefore.contains(f.name())) {
                    expectedNewFiles++;
                }
            }
            assertTrue(expectedNewFiles > 0, "expected the third flush to introduce at least one new file");
            assertTrue(expectedNewFiles < filesAfter.size(), "expected some files to be shared with the earlier recovery");

            RecoveryState state = env.newState();
            IndexShard targetShard2 = env.runRecovery(state);
            try {
                assertEquals(expectedNewFiles, state.filesRecovered());
                assertEquals(env.sourceShard.docCount(), targetShard2.docCount());
                for (int i = 0; i < 12; i++) {
                    assertTrue(targetShard2.get("doc-" + i).exists());
                }
            } finally {
                targetShard2.close();
            }
        }
    }

    @Test
    public void phase2OnlyReplayWhenFileSetsAlreadyMatch() throws Exception {
        try (Env env = new Env()) {
            for (int i = 0; i < 5; i++) {
                env.sourceShard.index("doc-" + i, doc("v" + i));
            }
            env.sourceShard.flush(true);

            copyDirectory(env.sourcePath.resolve("index"), env.targetPath.resolve("index"));

            for (int i = 5; i < 8; i++) {
                env.sourceShard.index("doc-" + i, doc("v" + i));
            }

            RecoveryState state = env.newState();
            IndexShard targetShard = env.runRecovery(state);
            try {
                assertEquals(0, state.filesRecovered());
                assertEquals(env.sourceShard.docCount(), targetShard.docCount());
                for (int i = 0; i < 8; i++) {
                    assertTrue(targetShard.get("doc-" + i).exists(), "expected doc-" + i + " to exist on target");
                }
            } finally {
                targetShard.close();
            }
        }
    }
}
