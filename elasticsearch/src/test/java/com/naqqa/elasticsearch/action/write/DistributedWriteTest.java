package com.naqqa.elasticsearch.action.write;

import com.naqqa.elasticsearch.analysis.registry.AnalysisRegistry;
import com.naqqa.elasticsearch.analysis.registry.IndexAnalyzers;
import com.naqqa.elasticsearch.cluster.routing.ShardId;
import com.naqqa.elasticsearch.cluster.state.ClusterState;
import com.naqqa.elasticsearch.cluster.state.IndexMetadata;
import com.naqqa.elasticsearch.cluster.state.Metadata;
import com.naqqa.elasticsearch.cluster.state.Settings;
import com.naqqa.elasticsearch.common.json.JsonValue;
import com.naqqa.elasticsearch.common.unit.TimeValue;
import com.naqqa.elasticsearch.common.threadpool.ThreadPool;
import com.naqqa.elasticsearch.index.engine.EngineConfig;
import com.naqqa.elasticsearch.index.engine.IndexOperation;
import com.naqqa.elasticsearch.index.mapper.MapperService;
import com.naqqa.elasticsearch.index.replication.ReplicaFailureListener;
import com.naqqa.elasticsearch.index.replication.ReplicationGroup;
import com.naqqa.elasticsearch.index.replication.ShardCopy;
import com.naqqa.elasticsearch.index.replication.WaitForActiveShards;
import com.naqqa.elasticsearch.index.shard.IndexShard;
import com.naqqa.elasticsearch.index.translog.TranslogConfig;
import com.naqqa.elasticsearch.rest.document.DocumentActionService;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class DistributedWriteTest {

    private static MapperService newMapperService(String indexName) {
        IndexAnalyzers analyzers = new AnalysisRegistry().build(Map.of());
        MapperService ms = new MapperService(analyzers, com.naqqa.elasticsearch.common.settings.Settings.EMPTY, indexName);
        ms.putMapping(Map.of("properties", Map.of("title", Map.of("type", "text"))));
        return ms;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> decode(com.naqqa.elasticsearch.index.engine.GetResult result) {
        return (Map<String, Object>) JsonValue.parse(result.source().toBytesArray()).toJava();
    }

    private static final class Node implements AutoCloseable {
        final String allocationId;
        final Path path;
        final IndexShard shard;
        final ThreadPool pool;
        final TransportService transportService;

        Node(String nodeId, String allocationId, String indexName) throws IOException {
            this.allocationId = allocationId;
            this.path = Files.createTempDirectory("write-test");
            MapperService mapperService = newMapperService(indexName);
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

    private static final class ShardCluster implements AutoCloseable {
        final ShardId shardId;
        final Node primary;
        final List<Node> replicas = new ArrayList<>();
        final ReplicationGroup group;

        ShardCluster(String indexName, int shardNum, int numReplicas) throws IOException {
            this.shardId = new ShardId(indexName, shardNum);
            this.primary = new Node("P" + shardNum, "alloc-P" + shardNum, indexName);
            ReplicaFailureListener listener = (sid, allocId, cause) -> {
            };
            this.group = new ReplicationGroup(shardId, primary.asCopy(ShardCopy.Role.PRIMARY), listener);
            for (int i = 0; i < numReplicas; i++) {
                Node r = new Node("R" + shardNum + "-" + i, "alloc-R" + shardNum + "-" + i, indexName);
                replicas.add(r);
                group.addInSyncReplica(r.asCopy(ShardCopy.Role.IN_SYNC_REPLICA));
            }
        }

        @Override
        public void close() {
            primary.close();
            for (Node r : replicas) {
                r.close();
            }
        }
    }

    private static ClusterState singleIndexState(String index, int numShards) {
        IndexMetadata meta = IndexMetadata.builder(index)
            .settings(Settings.builder().put("index.number_of_shards", numShards).build())
            .build();
        return ClusterState.builder("write-test-cluster").metadata(Metadata.builder().put(meta).build()).build();
    }

    private static DocumentActionService.IndexRequest indexReq(String index, String id, Map<String, Object> source, String refresh) {
        return new DocumentActionService.IndexRequest(index, id, source, null, null, null, null, null, "index", refresh, null);
    }

    @Test
    public void testIndexGetDeleteRoundTrip() throws Exception {
        String index = "rt-index";
        ClusterState state = singleIndexState(index, 1);
        try (ShardCluster cluster = new ShardCluster(index, 0, 2)) {
            Map<ShardId, ReplicationGroup> groups = Map.of(cluster.shardId, cluster.group);
            RelocationAwareRouter router = new RelocationAwareRouter(() -> state, groups);
            TransportIndexAction indexAction = new TransportIndexAction(router);
            TransportDeleteAction deleteAction = new TransportDeleteAction(router);

            DocumentActionService.IndexResult result = indexAction.execute(indexReq(index, "doc1", Map.of("title", "hello"), "false"));
            Assert.assertTrue(result.created());
            Assert.assertEquals("created", result.result());

            Assert.assertTrue(cluster.primary.shard.get("doc1").exists());
            for (Node r : cluster.replicas) {
                Assert.assertTrue(r.shard.get("doc1").exists());
                Assert.assertEquals("hello", decode(r.shard.get("doc1")).get("title"));
            }

            DocumentActionService.DeleteRequest delReq = new DocumentActionService.DeleteRequest(index, "doc1", null,
                null, null, null, null, "false");
            DocumentActionService.DeleteResult delResult = deleteAction.execute(delReq);
            Assert.assertTrue(delResult.found());
            Assert.assertEquals("deleted", delResult.result());

            Assert.assertFalse(cluster.primary.shard.get("doc1").exists());
            for (Node r : cluster.replicas) {
                Assert.assertFalse(r.shard.get("doc1").exists());
            }
        }
    }

    @Test
    public void testUpdateRetryOnConflictResolvesConcurrentVersionBump() throws Exception {
        String index = "conflict-index";
        ClusterState state = singleIndexState(index, 1);
        try (ShardCluster cluster = new ShardCluster(index, 0, 0)) {
            Map<ShardId, ReplicationGroup> groups = Map.of(cluster.shardId, cluster.group);
            RelocationAwareRouter router = new RelocationAwareRouter(() -> state, groups);
            TransportIndexAction indexAction = new TransportIndexAction(router);
            indexAction.execute(indexReq(index, "doc1", Map.of("title", "original"), "false"));

            AtomicInteger scriptCalls = new AtomicInteger();
            UpdateScriptExecutor conflictingExecutor = (scriptDef, currentSource) -> {
                int callNum = scriptCalls.incrementAndGet();
                if (callNum == 1) {
                    try {
                        cluster.group.replicateIndex(IndexOperation.of("doc1", Map.of("title", "concurrent-writer")),
                            WaitForActiveShards.ALL);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
                Map<String, Object> merged = new LinkedHashMap<>(currentSource);
                merged.put("title", "updated-by-script");
                return merged;
            };
            TransportUpdateAction updateAction = new TransportUpdateAction(router, conflictingExecutor);
            DocumentActionService.UpdateRequest req = new DocumentActionService.UpdateRequest(index, "doc1", null, null,
                false, true, 3, Map.of("source", "noop"), null, null, "false", true);
            DocumentActionService.UpdateResult result = updateAction.execute(req);

            Assert.assertEquals("updated", result.result());
            Assert.assertEquals(2, scriptCalls.get());
            Assert.assertEquals("updated-by-script", result.getSource().get("title"));
        }
    }

    @Test
    public void testUpsertCreatesDocWhenAbsent() throws Exception {
        String index = "upsert-index";
        ClusterState state = singleIndexState(index, 1);
        try (ShardCluster cluster = new ShardCluster(index, 0, 1)) {
            Map<ShardId, ReplicationGroup> groups = Map.of(cluster.shardId, cluster.group);
            RelocationAwareRouter router = new RelocationAwareRouter(() -> state, groups);
            TransportUpdateAction updateAction = new TransportUpdateAction(router);

            DocumentActionService.UpdateRequest req = new DocumentActionService.UpdateRequest(index, "newdoc", null,
                Map.of("title", "upserted"), false, true, 0, null, null, null, "false", true);
            DocumentActionService.UpdateResult result = updateAction.execute(req);

            Assert.assertEquals("created", result.result());
            Assert.assertEquals("upserted", result.getSource().get("title"));
            Assert.assertTrue(cluster.primary.shard.get("newdoc").exists());
        }
    }

    @Test
    public void testDetectNoopSkipsUpdate() throws Exception {
        String index = "noop-index";
        ClusterState state = singleIndexState(index, 1);
        try (ShardCluster cluster = new ShardCluster(index, 0, 0)) {
            Map<ShardId, ReplicationGroup> groups = Map.of(cluster.shardId, cluster.group);
            RelocationAwareRouter router = new RelocationAwareRouter(() -> state, groups);
            TransportIndexAction indexAction = new TransportIndexAction(router);
            TransportUpdateAction updateAction = new TransportUpdateAction(router);

            DocumentActionService.IndexResult indexResult = indexAction.execute(indexReq(index, "doc1", Map.of("title", "same"), "false"));
            long versionBefore = indexResult.version();

            DocumentActionService.UpdateRequest req = new DocumentActionService.UpdateRequest(index, "doc1",
                Map.of("title", "same"), null, false, true, 0, null, null, null, "false", true);
            DocumentActionService.UpdateResult result = updateAction.execute(req);

            Assert.assertEquals("noop", result.result());
            Assert.assertTrue(result.noop());
            Assert.assertEquals(versionBefore, result.version());
        }
    }

    @Test
    public void testBulkAcrossTwoShardsPreservesOrderWithPartialFailure() throws Exception {
        String index = "bulk-index";
        IndexMetadata meta = IndexMetadata.builder(index)
            .settings(Settings.builder().put("index.number_of_shards", 2).build())
            .build();
        ClusterState state = ClusterState.builder("bulk-cluster").metadata(Metadata.builder().put(meta).build()).build();

        try (ShardCluster shard0 = new ShardCluster(index, 0, 0);
             ShardCluster shard1 = new ShardCluster(index, 1, 0)) {

            String docShard0a = idForShard(meta, 0, "a");
            String docShard0b = idForShard(meta, 0, "b");
            String docShard1a = idForShard(meta, 1, "a");
            String missingDocOnShard1 = idForShard(meta, 1, "missing");

            Map<ShardId, ReplicationGroup> groups = new LinkedHashMap<>();
            groups.put(shard0.shardId, shard0.group);
            groups.put(shard1.shardId, shard1.group);
            RelocationAwareRouter router = new RelocationAwareRouter(() -> state, groups);
            TransportIndexAction indexAction = new TransportIndexAction(router);
            TransportDeleteAction deleteAction = new TransportDeleteAction(router);
            TransportUpdateAction updateAction = new TransportUpdateAction(router);
            BulkCoordinator coordinator = new BulkCoordinator(router, indexAction, deleteAction, updateAction);

            List<DocumentActionService.BulkItem> items = List.of(
                new DocumentActionService.BulkItem("index", null, docShard0a, Map.of("title", "s0-a"), null, null,
                    false, null, null, null, null, null, null),
                new DocumentActionService.BulkItem("index", null, docShard1a, Map.of("title", "s1-a"), null, null,
                    false, null, null, null, null, null, null),
                new DocumentActionService.BulkItem("update", null, missingDocOnShard1, null, Map.of("title", "x"),
                    null, false, null, null, null, null, null, null),
                new DocumentActionService.BulkItem("index", null, docShard0b, Map.of("title", "s0-b"), null, null,
                    false, null, null, null, null, null, null)
            );

            BulkResponse response = coordinator.execute(new BulkRequest(items, index, "false"));
            List<DocumentActionService.BulkItemResult> results = response.items();

            Assert.assertEquals(4, results.size());
            Assert.assertEquals(docShard0a, results.get(0).id());
            Assert.assertNull(results.get(0).error());
            Assert.assertEquals(201, results.get(0).status());

            Assert.assertEquals(docShard1a, results.get(1).id());
            Assert.assertNull(results.get(1).error());
            Assert.assertEquals(201, results.get(1).status());

            Assert.assertEquals(missingDocOnShard1, results.get(2).id());
            Assert.assertNotNull(results.get(2).error());
            Assert.assertEquals(404, results.get(2).status());

            Assert.assertEquals(docShard0b, results.get(3).id());
            Assert.assertNull(results.get(3).error());
            Assert.assertEquals(201, results.get(3).status());

            Assert.assertTrue(shard0.primary.shard.get(docShard0a).exists());
            Assert.assertTrue(shard0.primary.shard.get(docShard0b).exists());
            Assert.assertTrue(shard1.primary.shard.get(docShard1a).exists());
        }
    }

    private static String idForShard(IndexMetadata meta, int desiredShard, String salt) {
        for (int i = 0; i < 10_000; i++) {
            String id = "doc-" + salt + "-" + i;
            if (ShardRouter.computeShardId(meta, id, null) == desiredShard) {
                return id;
            }
        }
        throw new IllegalStateException("could not find an id hashing to shard " + desiredShard);
    }

    @Test
    public void testRefreshImmediateTriggersSynchronousRefresh() throws Exception {
        String index = "refresh-index";
        ClusterState state = singleIndexState(index, 1);
        try (ShardCluster cluster = new ShardCluster(index, 0, 0)) {
            Map<ShardId, ReplicationGroup> groups = Map.of(cluster.shardId, cluster.group);
            RelocationAwareRouter router = new RelocationAwareRouter(() -> state, groups);
            TransportIndexAction indexAction = new TransportIndexAction(router);

            indexAction.execute(indexReq(index, "doc1", Map.of("title", "no-refresh"), "false"));
            int segmentsBefore = cluster.primary.shard.segmentCount();

            indexAction.execute(indexReq(index, "doc2", Map.of("title", "with-refresh"), "true"));
            int segmentsAfter = cluster.primary.shard.segmentCount();

            Assert.assertTrue(segmentsAfter > segmentsBefore,
                "expected refresh=true to create a new visible segment, before=" + segmentsBefore + " after=" + segmentsAfter);
        }
    }

    @Test
    public void testStalePrimaryTriggersExactlyOneRetryThenSucceeds() throws Exception {
        String index = "stale-index";
        ClusterState state = singleIndexState(index, 1);
        try (ShardCluster cluster = new ShardCluster(index, 0, 1)) {
            Node replicaNode = cluster.replicas.get(0);
            ShardCopy replicaCopy = cluster.group.inSyncReplicasSnapshot().get(0);
            replicaCopy.primaryContext().assertNotStale(2L);

            Map<ShardId, ReplicationGroup> groups = Map.of(cluster.shardId, cluster.group);
            AtomicInteger retryCount = new AtomicInteger();
            RelocationRetryListener listener = (shardId, cause) -> {
                retryCount.incrementAndGet();
                try {
                    cluster.group.promoteReplicaToPrimary(replicaCopy.allocationId());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            };
            RelocationAwareRouter router = new RelocationAwareRouter(() -> state, groups, 1, listener);
            TransportIndexAction indexAction = new TransportIndexAction(router);

            DocumentActionService.IndexResult result = indexAction.execute(indexReq(index, "doc1", Map.of("title", "hello"), "false"));

            Assert.assertEquals("created", result.result());
            Assert.assertEquals(1, retryCount.get());
            Assert.assertTrue(replicaNode.shard.get("doc1").exists());
            Assert.assertSame(replicaCopy, cluster.group.primary());
        }
    }
}
