package com.naqqa.elasticsearch.index.replication;

import com.naqqa.elasticsearch.analysis.registry.AnalysisRegistry;
import com.naqqa.elasticsearch.analysis.registry.IndexAnalyzers;
import com.naqqa.elasticsearch.cluster.routing.ShardId;
import com.naqqa.elasticsearch.cluster.state.IndexMetadata;
import com.naqqa.elasticsearch.common.json.JsonValue;
import com.naqqa.elasticsearch.common.settings.Settings;
import com.naqqa.elasticsearch.common.threadpool.ThreadPool;
import com.naqqa.elasticsearch.common.unit.TimeValue;
import com.naqqa.elasticsearch.index.engine.EngineConfig;
import com.naqqa.elasticsearch.index.engine.GetResult;
import com.naqqa.elasticsearch.index.engine.IndexOperation;
import com.naqqa.elasticsearch.index.engine.IndexResult;
import com.naqqa.elasticsearch.index.mapper.MapperService;
import com.naqqa.elasticsearch.index.shard.IndexShard;
import com.naqqa.elasticsearch.index.translog.TranslogConfig;
import com.naqqa.elasticsearch.store.Directory;
import com.naqqa.elasticsearch.store.FSDirectory;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;
import com.naqqa.elasticsearch.transport.TransportService;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class ReplicationGroupTest {

    private static final ShardId SHARD_ID = new ShardId("test-index", 0);

    private static MapperService newMapperService() {
        IndexAnalyzers analyzers = new AnalysisRegistry().build(Map.of());
        MapperService ms = new MapperService(analyzers, Settings.EMPTY, "test-index");
        ms.putMapping(Map.of("properties", Map.of("title", Map.of("type", "text"))));
        return ms;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> decode(GetResult result) {
        return (Map<String, Object>) JsonValue.parse(result.source().toBytesArray()).toJava();
    }

    private static final class Node implements AutoCloseable {
        final String allocationId;
        final Path path;
        final IndexShard shard;
        final ThreadPool pool;
        final TransportService transportService;

        Node(String nodeId, String allocationId) throws IOException {
            this.allocationId = allocationId;
            this.path = Files.createTempDirectory("replication-test");
            MapperService mapperService = newMapperService();
            Directory directory = new FSDirectory(path.resolve("index"));
            TranslogConfig translogConfig = TranslogConfig.defaultConfig(path.resolve("translog"));
            EngineConfig config = EngineConfig.defaultConfig(path, directory, mapperService, translogConfig)
                .withRefreshInterval(TimeValue.MINUS_ONE);
            this.shard = IndexShard.open(config, mapperService);
            this.pool = new ThreadPool();
            this.transportService = new TransportService(nodeId, new InetSocketAddress("127.0.0.1", 0), pool);
            this.transportService.start();
        }

        ShardCopy asCopy(ShardCopy.Role role) {
            long term = shard.engine().config().primaryTerm();
            return new ShardCopy(allocationId, transportService.localNode(), shard, transportService, term, role);
        }

        @Override
        public void close() {
            try {
                shard.close();
            } catch (IOException ignored) {
            }
            try {
                transportService.close();
            } catch (Exception ignored) {
            }
            try {
                pool.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static final class RecordingFailureListener implements ReplicaFailureListener {
        final List<String> failedAllocationIds = new CopyOnWriteArrayList<>();
        volatile CountDownLatch latch;

        void expect(int count) {
            latch = new CountDownLatch(count);
        }

        boolean await(long millis) throws InterruptedException {
            return latch != null && latch.await(millis, TimeUnit.MILLISECONDS);
        }

        @Override
        public void onReplicaFailure(ShardId shardId, String allocationId, Exception cause) {
            failedAllocationIds.add(allocationId);
            if (latch != null) {
                latch.countDown();
            }
        }
    }

    @Test
    public void testIndexReplicatesToAllInSyncReplicas() throws Exception {
        Node primary = new Node("P", "alloc-P");
        Node r1 = new Node("R1", "alloc-R1");
        Node r2 = new Node("R2", "alloc-R2");
        try {
            RecordingFailureListener listener = new RecordingFailureListener();
            ReplicationGroup group = new ReplicationGroup(SHARD_ID, primary.asCopy(ShardCopy.Role.PRIMARY), listener);
            group.addInSyncReplica(r1.asCopy(ShardCopy.Role.IN_SYNC_REPLICA));
            group.addInSyncReplica(r2.asCopy(ShardCopy.Role.IN_SYNC_REPLICA));

            IndexResult result = group.replicateIndex(IndexOperation.of("doc1", Map.of("title", "hello")), WaitForActiveShards.ALL);
            Assert.assertTrue(result.success());

            Assert.assertTrue(primary.shard.get("doc1").exists());
            GetResult onR1 = r1.shard.get("doc1");
            GetResult onR2 = r2.shard.get("doc1");
            Assert.assertTrue(onR1.exists());
            Assert.assertTrue(onR2.exists());
            Assert.assertEquals("hello", decode(onR1).get("title"));
            Assert.assertEquals("hello", decode(onR2).get("title"));
            Assert.assertTrue(listener.failedAllocationIds.isEmpty());
        } finally {
            primary.close();
            r1.close();
            r2.close();
        }
    }

    @Test
    public void testReplicationGroupCanBeSeededFromClusterState() throws Exception {
        Node primary = new Node("P", "alloc-P");
        Node r1 = new Node("R1", "alloc-R1");
        Node r2 = new Node("R2", "alloc-R2");
        try {
            IndexMetadata metadata = IndexMetadata.builder("test-index")
                .putInSyncAllocationIds(0, Set.of("alloc-P", "alloc-R1"))
                .primaryTerm(0, 1L)
                .build();
            RecordingFailureListener listener = new RecordingFailureListener();
            List<ShardCopy> candidates = List.of(
                r1.asCopy(ShardCopy.Role.INITIALIZING),
                r2.asCopy(ShardCopy.Role.INITIALIZING)
            );
            ReplicationGroup group = ReplicationGroup.fromClusterState(metadata, SHARD_ID,
                primary.asCopy(ShardCopy.Role.PRIMARY), candidates, listener);

            Assert.assertEquals(1, group.inSyncReplicasSnapshot().size());
            Assert.assertEquals("alloc-R1", group.inSyncReplicasSnapshot().get(0).allocationId());
            Assert.assertEquals(1, group.initializingReplicasSnapshot().size());
            Assert.assertEquals("alloc-R2", group.initializingReplicasSnapshot().get(0).allocationId());

            IndexResult result = group.replicateIndex(IndexOperation.of("doc1", Map.of("title", "seeded")), WaitForActiveShards.ALL);
            Assert.assertTrue(result.success());
            Assert.assertTrue(r1.shard.get("doc1").exists());
            Assert.assertFalse(r2.shard.get("doc1").exists());
        } finally {
            primary.close();
            r1.close();
            r2.close();
        }
    }

    @Test
    public void testStalePrimaryIsRejectedAndPrimaryStepsDown() throws Exception {
        Node primary = new Node("P", "alloc-P");
        Node r1 = new Node("R1", "alloc-R1");
        try {
            RecordingFailureListener listener = new RecordingFailureListener();
            ShardCopy primaryCopy = primary.asCopy(ShardCopy.Role.PRIMARY);
            ReplicationGroup group = new ReplicationGroup(SHARD_ID, primaryCopy, listener);
            ShardCopy replicaCopy = r1.asCopy(ShardCopy.Role.IN_SYNC_REPLICA);
            group.addInSyncReplica(replicaCopy);

            replicaCopy.primaryContext().assertNotStale(5L);

            StalePrimaryException stale = Assert.assertThrows(StalePrimaryException.class, () ->
                group.replicateIndex(IndexOperation.of("doc1", Map.of("title", "x")), WaitForActiveShards.ALL));
            Assert.assertNotNull(stale);
            Assert.assertTrue(primaryCopy.primaryContext().isSteppedDown());

            Assert.assertThrows(PrimarySteppedDownException.class, () ->
                group.replicateIndex(IndexOperation.of("doc2", Map.of("title", "y")), WaitForActiveShards.ALL));
        } finally {
            primary.close();
            r1.close();
        }
    }

    @Test
    public void testDeadReplicaDoesNotBlockSuccessAndIsReportedAsFailed() throws Exception {
        Node primary = new Node("P", "alloc-P");
        Node r1 = new Node("R1", "alloc-R1");
        Node r2 = new Node("R2", "alloc-R2");
        try {
            RecordingFailureListener listener = new RecordingFailureListener();
            listener.expect(1);
            ShardCopy primaryCopy = primary.asCopy(ShardCopy.Role.PRIMARY);
            ReplicationGroup group = new ReplicationGroup(SHARD_ID, primaryCopy, listener);
            group.withReplicaTimeoutMillis(500);
            group.addInSyncReplica(r1.asCopy(ShardCopy.Role.IN_SYNC_REPLICA));
            group.addInSyncReplica(r2.asCopy(ShardCopy.Role.IN_SYNC_REPLICA));

            r2.transportService.close();

            IndexResult result = group.replicateIndex(IndexOperation.of("doc1", Map.of("title", "x")), WaitForActiveShards.of(2));
            Assert.assertTrue(result.success());
            Assert.assertTrue(primary.shard.get("doc1").exists());
            Assert.assertTrue(r1.shard.get("doc1").exists());

            Assert.assertTrue(listener.await(5000), "expected failure listener to be notified about the dead replica");
            Assert.assertEquals(1, listener.failedAllocationIds.size());
            Assert.assertEquals("alloc-R2", listener.failedAllocationIds.get(0));
            Assert.assertEquals(1, group.inSyncReplicasSnapshot().size());
        } finally {
            primary.close();
            r1.close();
            try {
                r2.shard.close();
            } catch (IOException ignored) {
            }
            try {
                r2.pool.close();
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    public void testPromoteReplicaToPrimaryResyncsGapOpsToRemainingReplica() throws Exception {
        Node primary = new Node("P", "alloc-P");
        Node r1 = new Node("R1", "alloc-R1");
        Node r2 = new Node("R2", "alloc-R2");
        try {
            RecordingFailureListener listener = new RecordingFailureListener();
            ShardCopy primaryCopy = primary.asCopy(ShardCopy.Role.PRIMARY);
            ReplicationGroup group = new ReplicationGroup(SHARD_ID, primaryCopy, listener);
            ShardCopy r1Copy = r1.asCopy(ShardCopy.Role.IN_SYNC_REPLICA);
            ShardCopy r2Copy = r2.asCopy(ShardCopy.Role.IN_SYNC_REPLICA);
            group.addInSyncReplica(r1Copy);
            group.addInSyncReplica(r2Copy);

            IndexResult firstResult = group.replicateIndex(IndexOperation.of("doc1", Map.of("title", "one")), WaitForActiveShards.ALL);
            Assert.assertTrue(firstResult.success());

            IndexResult gapOnPrimary = primary.shard.index(IndexOperation.of("doc2", Map.of("title", "two")));
            IndexResult gapOnR1 = r1.shard.index(IndexOperation.of("doc2", Map.of("title", "two")));
            Assert.assertTrue(gapOnPrimary.success());
            Assert.assertTrue(gapOnR1.success());
            group.recordPrimaryCheckpoint(gapOnPrimary.seqNo());
            group.recordReplicaCheckpoint(r1Copy.allocationId(), gapOnR1.seqNo());

            Assert.assertFalse(r2.shard.get("doc2").exists(), "replica R2 should not have the gap op yet");

            PrimaryContext newPrimaryContext = group.promoteReplicaToPrimary(r1Copy.allocationId());
            Assert.assertNotNull(newPrimaryContext);
            Assert.assertSame(r1Copy, group.primary());

            GetResult resynced = r2.shard.get("doc2");
            Assert.assertTrue(resynced.exists(), "resync should have replayed the gap op to R2");
            Assert.assertEquals("two", decode(resynced).get("title"));

            IndexResult afterPromotion = group.replicateIndex(IndexOperation.of("doc3", Map.of("title", "three")), WaitForActiveShards.ALL);
            Assert.assertTrue(afterPromotion.success());
            Assert.assertTrue(r1.shard.get("doc3").exists());
            Assert.assertTrue(r2.shard.get("doc3").exists());

            List<String> docsOnNewPrimary = new ArrayList<>();
            for (String id : List.of("doc1", "doc2", "doc3")) {
                if (r1.shard.get(id).exists()) {
                    docsOnNewPrimary.add(id);
                }
            }
            List<String> docsOnR2 = new ArrayList<>();
            for (String id : List.of("doc1", "doc2", "doc3")) {
                if (r2.shard.get(id).exists()) {
                    docsOnR2.add(id);
                }
            }
            Assert.assertEquals(docsOnNewPrimary, docsOnR2);
        } finally {
            primary.close();
            r1.close();
            r2.close();
        }
    }
}
