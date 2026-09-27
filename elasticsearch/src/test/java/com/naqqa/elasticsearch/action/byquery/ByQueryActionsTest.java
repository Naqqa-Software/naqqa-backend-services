package com.naqqa.elasticsearch.action.byquery;

import com.naqqa.elasticsearch.action.search.SearchCoordinator;
import com.naqqa.elasticsearch.action.write.RelocationAwareRouter;
import com.naqqa.elasticsearch.action.write.ShardRouter;
import com.naqqa.elasticsearch.action.write.SourceUtils;
import com.naqqa.elasticsearch.cluster.routing.RoutingTable;
import com.naqqa.elasticsearch.cluster.routing.ShardId;
import com.naqqa.elasticsearch.cluster.state.ClusterState;
import com.naqqa.elasticsearch.cluster.state.Metadata;
import com.naqqa.elasticsearch.index.engine.GetResult;
import com.naqqa.elasticsearch.index.replication.ReplicationGroup;
import com.naqqa.elasticsearch.monitor.tasks.Task;
import com.naqqa.elasticsearch.monitor.tasks.TaskManager;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;
import com.naqqa.elasticsearch.transport.DiscoveryNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

public final class ByQueryActionsTest {

    @Test
    public void deleteByQueryRemovesOnlyMatchingDocsAcrossShardsAndBatches() throws Exception {
        String index = "delete-idx";
        try (ByQueryTestCluster cluster = new ByQueryTestCluster(index, 2)) {
            List<String> toDelete = new ArrayList<>();
            List<String> toKeep = new ArrayList<>();
            for (int shardNum = 0; shardNum < 2; shardNum++) {
                for (int i = 0; i < 3; i++) {
                    String delId = cluster.idForShard(shardNum, "del-" + shardNum + "-" + i);
                    cluster.indexDirect(shardNum, delId, Map.of("status", "delete-me"));
                    toDelete.add(delId);
                    String keepId = cluster.idForShard(shardNum, "keep-" + shardNum + "-" + i);
                    cluster.indexDirect(shardNum, keepId, Map.of("status", "keep"));
                    toKeep.add(keepId);
                }
            }
            cluster.refreshAll();

            RelocationAwareRouter router = cluster.router();
            DeleteByQueryAction action = new DeleteByQueryAction(router, cluster.coordinator(), cluster.taskManager(),
                new ThrottleRegistry());
            ByQueryOptions options = new ByQueryOptions(2, -1d, -1L, 30_000L, null, "false", 0, true, "index");

            BulkByScrollResponse response = action.execute(index, Map.of("term", Map.of("status", "delete-me")), options);

            Assert.assertEquals(6, response.deleted());
            Assert.assertTrue(response.batches() >= 3, "expected multiple batches, got " + response.batches());

            for (String id : toDelete) {
                boolean stillExists = cluster.shard(ShardRouter.computeShardId(cluster.metadata, id, null)).get(id).exists();
                Assert.assertFalse(stillExists, "expected " + id + " to be deleted");
            }
            for (String id : toKeep) {
                boolean exists = cluster.shard(ShardRouter.computeShardId(cluster.metadata, id, null)).get(id).exists();
                Assert.assertTrue(exists, "expected " + id + " to still exist");
            }
        }
    }

    @Test
    public void updateByQueryAppliesTransformToMatchedDocs() throws Exception {
        String index = "update-idx";
        try (ByQueryTestCluster cluster = new ByQueryTestCluster(index, 1)) {
            for (int i = 0; i < 3; i++) {
                cluster.indexDirect(0, "doc-" + i, Map.of("status", "active", "count", (long) i));
            }
            cluster.refreshAll();

            RelocationAwareRouter router = cluster.router();
            UpdateByQueryAction action = new UpdateByQueryAction(router, cluster.coordinator(), cluster.taskManager(),
                new ThrottleRegistry());
            ByQueryOptions options = new ByQueryOptions(1, -1d, -1L, 30_000L, null, "false", 0, true, "index");

            UnaryOperator<Map<String, Object>> transform = source -> {
                Map<String, Object> updated = new LinkedHashMap<>(source);
                long current = ((Number) updated.getOrDefault("count", 0)).longValue();
                updated.put("count", current + 100);
                return updated;
            };

            BulkByScrollResponse response = action.execute(index, Map.of("term", Map.of("status", "active")), transform, options);

            Assert.assertEquals(3, response.updated());
            Assert.assertEquals(0, response.noops());

            for (int i = 0; i < 3; i++) {
                GetResult result = cluster.shard(0).get("doc-" + i);
                Assert.assertTrue(result.exists());
                Map<String, Object> source = SourceUtils.decode(result.source());
                Assert.assertEquals(100L + i, ((Number) source.get("count")).longValue());
            }
        }
    }

    @Test
    public void reindexCopiesMatchingDocsFromSourceToFreshDestination() throws Exception {
        String sourceIndex = "reindex-src";
        String destIndex = "reindex-dest";
        try (ByQueryTestCluster src = new ByQueryTestCluster(sourceIndex, 1);
             ByQueryTestCluster dest = new ByQueryTestCluster(destIndex, 1)) {

            src.indexDirect(0, "a1", Map.of("status", "active", "title", "alpha"));
            src.indexDirect(0, "a2", Map.of("status", "active", "title", "beta"));
            src.indexDirect(0, "a3", Map.of("status", "inactive", "title", "gamma"));
            src.refreshAll();

            Map<ShardId, ReplicationGroup> combinedGroups = new LinkedHashMap<>();
            combinedGroups.putAll(src.groups);
            combinedGroups.putAll(dest.groups);

            Metadata metadata = Metadata.builder().put(src.metadata).put(dest.metadata).build();
            RoutingTable combinedRouting = RoutingTable.builder()
                .add(src.routingTable.index(sourceIndex))
                .add(dest.routingTable.index(destIndex))
                .build();
            ClusterState combinedState = ClusterState.builder("combined-cluster")
                .metadata(metadata)
                .routingTable(combinedRouting)
                .build();
            RelocationAwareRouter router = new RelocationAwareRouter(() -> combinedState, combinedGroups);

            Map<String, DiscoveryNode> combinedNodeTable = new LinkedHashMap<>();
            combinedNodeTable.putAll(src.nodeTable);
            combinedNodeTable.putAll(dest.nodeTable);
            SearchCoordinator coordinator = new SearchCoordinator(src.nodes.get(0).transportService, combinedNodeTable);

            TaskManager taskManager = new TaskManager("combined-node");
            ReindexAction action = new ReindexAction(router, coordinator, taskManager, new ThrottleRegistry());
            ByQueryOptions options = new ByQueryOptions(1, -1d, -1L, 30_000L, null, "false", 0, true, "index");

            BulkByScrollResponse response = action.execute(sourceIndex, Map.of("term", Map.of("status", "active")),
                destIndex, options);

            Assert.assertEquals(2, response.created());

            GetResult a1 = dest.shard(0).get("a1");
            Assert.assertTrue(a1.exists());
            Assert.assertEquals("alpha", SourceUtils.decode(a1.source()).get("title"));

            GetResult a2 = dest.shard(0).get("a2");
            Assert.assertTrue(a2.exists());

            GetResult a3 = dest.shard(0).get("a3");
            Assert.assertFalse(a3.exists());
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    public void termVectorsReturnsPositionsAndOffsetsForAnalyzedField() throws Exception {
        String index = "tv-idx";
        try (ByQueryTestCluster cluster = new ByQueryTestCluster(index, 1)) {
            String text = "quick brown fox jumps";
            cluster.indexDirect(0, "doc1", Map.of("body", text));
            cluster.refreshAll();

            RelocationAwareRouter router = cluster.router();
            TermVectorsAction action = new TermVectorsAction(router);
            Map<String, Object> result = action.get(index, "doc1", List.of("body"));

            Assert.assertEquals(Boolean.TRUE, result.get("found"));
            Map<String, Object> termVectors = (Map<String, Object>) result.get("term_vectors");
            Map<String, Object> bodyTv = (Map<String, Object>) termVectors.get("body");
            Map<String, Object> terms = (Map<String, Object>) bodyTv.get("terms");
            Map<String, Object> foxTerm = (Map<String, Object>) terms.get("fox");
            Assert.assertNotNull(foxTerm);
            Assert.assertEquals(1, foxTerm.get("term_freq"));
            List<Map<String, Object>> tokens = (List<Map<String, Object>>) foxTerm.get("tokens");
            Assert.assertEquals(1, tokens.size());
            Map<String, Object> token = tokens.get(0);
            Assert.assertEquals(text.indexOf("fox"), token.get("start_offset"));
            Assert.assertEquals(text.indexOf("fox") + 3, token.get("end_offset"));
        }
    }

    @Test
    public void multiTermVectorsReturnsOneEntryPerRequestedDoc() throws Exception {
        String index = "mtv-idx";
        try (ByQueryTestCluster cluster = new ByQueryTestCluster(index, 1)) {
            cluster.indexDirect(0, "doc1", Map.of("body", "alpha beta"));
            cluster.indexDirect(0, "doc2", Map.of("body", "gamma delta"));
            cluster.refreshAll();

            RelocationAwareRouter router = cluster.router();
            MultiTermVectorsAction action = new MultiTermVectorsAction(router);
            Map<String, Object> result = action.get(index, List.of("doc1", "doc2"), List.of("body"));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> docs = (List<Map<String, Object>>) result.get("docs");
            Assert.assertEquals(2, docs.size());
            Assert.assertEquals("doc1", docs.get(0).get("_id"));
            Assert.assertEquals("doc2", docs.get(1).get("_id"));
        }
    }

    @Test
    public void rethrottleReducesTotalRunTimeOfASlowBatchAction() throws Exception {
        String index = "rethrottle-idx";
        try (ByQueryTestCluster cluster = new ByQueryTestCluster(index, 1)) {
            for (int i = 0; i < 4; i++) {
                cluster.indexDirect(0, "doc-" + i, Map.of("status", "x"));
            }
            cluster.refreshAll();

            RelocationAwareRouter router = cluster.router();
            TaskManager taskManager = cluster.taskManager();
            ThrottleRegistry throttleRegistry = new ThrottleRegistry();
            DeleteByQueryAction action = new DeleteByQueryAction(router, cluster.coordinator(), taskManager, throttleRegistry);
            RethrottleAction rethrottle = new RethrottleAction(taskManager, throttleRegistry);

            ByQueryOptions options = new ByQueryOptions(1, 2d, -1L, 30_000L, null, "false", 0, true, "index");
            AtomicReference<BulkByScrollResponse> responseRef = new AtomicReference<>();
            AtomicReference<Exception> errorRef = new AtomicReference<>();
            long start = System.currentTimeMillis();
            Thread worker = new Thread(() -> {
                try {
                    responseRef.set(action.execute(index, Map.of("term", Map.of("status", "x")), options));
                } catch (IOException e) {
                    errorRef.set(e);
                }
            });
            worker.start();

            String taskId = null;
            for (int i = 0; i < 400 && taskId == null; i++) {
                List<Task> running = taskManager.list(DeleteByQueryAction.ACTION_NAME, null, null);
                if (!running.isEmpty()) {
                    taskId = running.get(0).taskId();
                } else {
                    Thread.sleep(5);
                }
            }
            Assert.assertNotNull(taskId);
            rethrottle.rethrottle(taskId, 10_000d);
            worker.join(15_000);

            Assert.assertNull(errorRef.get());
            long elapsed = System.currentTimeMillis() - start;
            Assert.assertTrue(elapsed < 1300, "expected rethrottle to shorten total run time, elapsed=" + elapsed);
            Assert.assertEquals(4, responseRef.get().deleted());
        }
    }

    @Test
    public void cancellationStopsBatchActionBeforeCompletingAllBatches() throws Exception {
        String index = "cancel-idx";
        try (ByQueryTestCluster cluster = new ByQueryTestCluster(index, 1)) {
            int totalDocs = 6;
            for (int i = 0; i < totalDocs; i++) {
                cluster.indexDirect(0, "doc-" + i, Map.of("status", "x"));
            }
            cluster.refreshAll();

            RelocationAwareRouter router = cluster.router();
            TaskManager taskManager = cluster.taskManager();
            DeleteByQueryAction action = new DeleteByQueryAction(router, cluster.coordinator(), taskManager, new ThrottleRegistry());

            ByQueryOptions options = new ByQueryOptions(1, 2d, -1L, 30_000L, null, "false", 0, true, "index");
            AtomicReference<BulkByScrollResponse> responseRef = new AtomicReference<>();
            AtomicReference<Exception> errorRef = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    responseRef.set(action.execute(index, Map.of("term", Map.of("status", "x")), options));
                } catch (IOException e) {
                    errorRef.set(e);
                }
            });
            worker.start();

            long taskLongId = -1;
            for (int i = 0; i < 400 && taskLongId < 0; i++) {
                List<Task> running = taskManager.list(DeleteByQueryAction.ACTION_NAME, null, null);
                if (!running.isEmpty()) {
                    taskLongId = running.get(0).id();
                } else {
                    Thread.sleep(5);
                }
            }
            Assert.assertTrue(taskLongId >= 0);
            taskManager.cancel(taskLongId, "test-cancel");
            worker.join(15_000);

            Assert.assertNull(errorRef.get());
            BulkByScrollResponse response = responseRef.get();
            Assert.assertNotNull(response);
            Assert.assertTrue(response.cancelled());
            Assert.assertTrue(response.deleted() < totalDocs,
                "expected cancellation before all docs processed, deleted=" + response.deleted());
        }
    }
}
