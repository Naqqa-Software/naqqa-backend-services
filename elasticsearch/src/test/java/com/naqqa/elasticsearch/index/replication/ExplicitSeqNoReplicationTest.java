package com.naqqa.elasticsearch.index.replication;

import com.naqqa.elasticsearch.analysis.registry.AnalysisRegistry;
import com.naqqa.elasticsearch.analysis.registry.IndexAnalyzers;
import com.naqqa.elasticsearch.cluster.routing.ShardId;
import com.naqqa.elasticsearch.common.json.JsonValue;
import com.naqqa.elasticsearch.common.json.JsonWriter;
import com.naqqa.elasticsearch.common.settings.Settings;
import com.naqqa.elasticsearch.common.threadpool.ThreadPool;
import com.naqqa.elasticsearch.common.unit.TimeValue;
import com.naqqa.elasticsearch.index.engine.DeleteOperation;
import com.naqqa.elasticsearch.index.engine.DeleteResult;
import com.naqqa.elasticsearch.index.engine.EngineConfig;
import com.naqqa.elasticsearch.index.engine.GetResult;
import com.naqqa.elasticsearch.index.engine.IndexOperation;
import com.naqqa.elasticsearch.index.engine.IndexResult;
import com.naqqa.elasticsearch.index.engine.NoOpOperation;
import com.naqqa.elasticsearch.index.engine.NoOpResult;
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
import java.util.List;
import java.util.Map;

public class ExplicitSeqNoReplicationTest {

    private static final ShardId SHARD_ID = new ShardId("test-index", 0);

    private static MapperService newMapperService() {
        IndexAnalyzers analyzers = new AnalysisRegistry().build(Map.of());
        MapperService ms = new MapperService(analyzers, Settings.EMPTY, "test-index");
        ms.putMapping(Map.of("properties", Map.of("title", Map.of("type", "text"))));
        return ms;
    }

    private static IndexShard newShard() throws IOException {
        Path path = Files.createTempDirectory("explicit-seqno-replication");
        MapperService mapperService = newMapperService();
        Directory directory = new FSDirectory(path.resolve("index"));
        TranslogConfig translogConfig = TranslogConfig.defaultConfig(path.resolve("translog"));
        EngineConfig config = EngineConfig.defaultConfig(path, directory, mapperService, translogConfig)
            .withRefreshInterval(TimeValue.MINUS_ONE);
        return IndexShard.open(config, mapperService);
    }

    @SuppressWarnings("unchecked")
    private static String title(GetResult result) {
        return (String) ((Map<String, Object>) JsonValue.parse(result.source().toBytesArray()).toJava()).get("title");
    }

    private static byte[] source(String title) {
        return JsonWriter.toJsonBytes(JsonValue.wrap(Map.of("title", title)), false);
    }

    private static void assertSameSeqNoHistory(IndexShard expected, IndexShard actual) {
        Assert.assertEquals(expected.localCheckpoint(), actual.localCheckpoint());
        Assert.assertEquals(expected.maxSeqNo(), actual.maxSeqNo());
        for (long s = 0; s <= expected.maxSeqNo() + 2; s++) {
            Assert.assertEquals(expected.hasProcessedSeqNo(s), actual.hasProcessedSeqNo(s), "processed mismatch at seq_no " + s);
        }
    }

    private static final class Node implements AutoCloseable {
        final String allocationId;
        final IndexShard shard;
        final ThreadPool pool;
        final TransportService transportService;

        Node(String nodeId, String allocationId) throws IOException {
            this.allocationId = allocationId;
            this.shard = newShard();
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

    @Test
    public void replicaProcessesExactlyThePrimarysSeqNosIncludingPreExistingHistory() throws Exception {
        Node primary = new Node("P", "alloc-P");
        Node r1 = new Node("R1", "alloc-R1");
        try {
            ReplicationGroup group = new ReplicationGroup(SHARD_ID, primary.asCopy(ShardCopy.Role.PRIMARY), (s, a, e) -> { });
            group.addInSyncReplica(r1.asCopy(ShardCopy.Role.IN_SYNC_REPLICA));

            primary.shard.noOp("primary-only-history");
            r1.shard.noOpAtSeqNo("primary-only-history", 0, 1);

            IndexResult i1 = group.replicateIndex(IndexOperation.of("doc1", Map.of("title", "one")), WaitForActiveShards.ALL);
            IndexResult i2 = group.replicateIndex(IndexOperation.of("doc2", Map.of("title", "two")), WaitForActiveShards.ALL);
            IndexResult i1b = group.replicateIndex(IndexOperation.of("doc1", Map.of("title", "one-b")), WaitForActiveShards.ALL);
            DeleteResult d2 = group.replicateDelete(DeleteOperation.of("doc2"), WaitForActiveShards.ALL);
            NoOpResult n = group.replicateNoOp(new NoOpOperation("filler"), WaitForActiveShards.ALL);

            Assert.assertEquals(1L, i1.seqNo());
            Assert.assertEquals(2L, i2.seqNo());
            Assert.assertEquals(3L, i1b.seqNo());
            Assert.assertEquals(4L, d2.seqNo());
            Assert.assertEquals(5L, n.seqNo());

            assertSameSeqNoHistory(primary.shard, r1.shard);
            Assert.assertEquals(5L, r1.shard.localCheckpoint());

            GetResult onPrimary = primary.shard.get("doc1");
            GetResult onReplica = r1.shard.get("doc1");
            Assert.assertEquals(onPrimary.seqNo(), onReplica.seqNo());
            Assert.assertEquals(onPrimary.version(), onReplica.version());
            Assert.assertEquals(onPrimary.primaryTerm(), onReplica.primaryTerm());
            Assert.assertEquals("one-b", title(onReplica));
            Assert.assertFalse(r1.shard.get("doc2").exists());

            Assert.assertEquals(5L, group.checkpointTracker().getLocalCheckpoint("alloc-R1"));
            Assert.assertEquals(5L, group.checkpointTracker().getGlobalCheckpoint());
        } finally {
            primary.close();
            r1.close();
        }
    }

    @Test
    public void reorderedReplicaRequestsConvergeToPrimarySeqNoHistory() throws Exception {
        IndexShard primary = newShard();
        IndexShard replica = newShard();
        try {
            IndexResult a1 = primary.index(IndexOperation.of("a", Map.of("title", "a1")));
            IndexResult b1 = primary.index(IndexOperation.of("b", Map.of("title", "b1")));
            IndexResult a2 = primary.index(IndexOperation.of("a", Map.of("title", "a2")));
            DeleteResult bDel = primary.delete(DeleteOperation.of("b"));

            List<ReplicationRequest> requests = List.of(
                ReplicationRequest.deleteAtSeqNo(SHARD_ID, 1, "b", bDel.seqNo(), bDel.primaryTerm(), bDel.version()),
                ReplicationRequest.indexAtSeqNo(SHARD_ID, 1, "a", null, source("a2"), a2.seqNo(), a2.primaryTerm(), a2.version()),
                ReplicationRequest.indexAtSeqNo(SHARD_ID, 1, "b", null, source("b1"), b1.seqNo(), b1.primaryTerm(), b1.version()),
                ReplicationRequest.indexAtSeqNo(SHARD_ID, 1, "a", null, source("a1"), a1.seqNo(), a1.primaryTerm(), a1.version()));

            ReplicationResponse first = ReplicationGroup.applyLocally(replica, requests.get(0));
            Assert.assertEquals(3L, first.seqNo());
            Assert.assertEquals(-1L, first.localCheckpoint());
            for (int i = 1; i < requests.size(); i++) {
                ReplicationGroup.applyLocally(replica, requests.get(i));
            }

            assertSameSeqNoHistory(primary, replica);
            Assert.assertEquals(3L, replica.localCheckpoint());
            Assert.assertEquals("a2", title(replica.get("a")));
            Assert.assertEquals(a2.seqNo(), replica.get("a").seqNo());
            Assert.assertEquals(a2.version(), replica.get("a").version());
            Assert.assertFalse(replica.get("b").exists());
        } finally {
            primary.close();
            replica.close();
        }
    }

    @Test
    public void duplicateReplicationRequestIsIdempotent() throws Exception {
        IndexShard replica = newShard();
        try {
            ReplicationRequest index = ReplicationRequest.indexAtSeqNo(SHARD_ID, 1, "a", null, source("first"), 0, 1, 1);
            ReplicationResponse r1 = ReplicationGroup.applyLocally(replica, index);
            Assert.assertEquals(0L, r1.seqNo());
            Assert.assertEquals(0L, r1.localCheckpoint());
            long opsBefore = replica.stats().translogNumOps();

            ReplicationResponse again = ReplicationGroup.applyLocally(replica, index);
            Assert.assertEquals(0L, again.seqNo());
            Assert.assertEquals(0L, again.localCheckpoint());

            ReplicationRequest conflicting = ReplicationRequest.indexAtSeqNo(SHARD_ID, 1, "a", null, source("second"), 0, 1, 1);
            ReplicationGroup.applyLocally(replica, conflicting);
            ReplicationGroup.applyLocally(replica, ReplicationRequest.deleteAtSeqNo(SHARD_ID, 1, "a", 0, 1, 2));
            ReplicationGroup.applyLocally(replica, ReplicationRequest.noOpAtSeqNo(SHARD_ID, 1, "dup", 0, 1));

            Assert.assertEquals(opsBefore, replica.stats().translogNumOps());
            Assert.assertEquals(0L, replica.maxSeqNo());
            Assert.assertEquals(0L, replica.localCheckpoint());
            Assert.assertTrue(replica.get("a").exists());
            Assert.assertEquals("first", title(replica.get("a")));
            Assert.assertEquals(1L, replica.get("a").version());
        } finally {
            replica.close();
        }
    }

    @Test
    public void resyncAfterPromotionDoesNotDoubleApplyOpsTheReplicaAlreadyHas() throws Exception {
        Node primary = new Node("P", "alloc-P");
        Node r1 = new Node("R1", "alloc-R1");
        Node r2 = new Node("R2", "alloc-R2");
        try {
            ReplicationGroup group = new ReplicationGroup(SHARD_ID, primary.asCopy(ShardCopy.Role.PRIMARY), (s, a, e) -> { });
            ShardCopy r1Copy = r1.asCopy(ShardCopy.Role.IN_SYNC_REPLICA);
            ShardCopy r2Copy = r2.asCopy(ShardCopy.Role.IN_SYNC_REPLICA);
            group.addInSyncReplica(r1Copy);
            group.addInSyncReplica(r2Copy);

            group.replicateIndex(IndexOperation.of("doc1", Map.of("title", "one")), WaitForActiveShards.ALL);

            IndexResult gap = primary.shard.index(IndexOperation.of("doc2", Map.of("title", "two")));
            r1.shard.indexAtSeqNo(IndexOperation.of("doc2", Map.of("title", "two")).withVersion(gap.version(),
                com.naqqa.elasticsearch.index.translog.VersionType.EXTERNAL), gap.seqNo(), gap.primaryTerm());
            IndexResult gap2 = primary.shard.index(IndexOperation.of("doc3", Map.of("title", "three")));
            r1.shard.indexAtSeqNo(IndexOperation.of("doc3", Map.of("title", "three")).withVersion(gap2.version(),
                com.naqqa.elasticsearch.index.translog.VersionType.EXTERNAL), gap2.seqNo(), gap2.primaryTerm());
            r2.shard.indexAtSeqNo(IndexOperation.of("doc3", Map.of("title", "three")).withVersion(gap2.version(),
                com.naqqa.elasticsearch.index.translog.VersionType.EXTERNAL), gap2.seqNo(), gap2.primaryTerm());
            long r2OpsBefore = r2.shard.stats().translogNumOps();

            Assert.assertEquals(0L, r2.shard.localCheckpoint());
            Assert.assertEquals(2L, r2.shard.maxSeqNo());

            group.promoteReplicaToPrimary(r1Copy.allocationId());

            assertSameSeqNoHistory(r1.shard, r2.shard);
            Assert.assertEquals(2L, r2.shard.localCheckpoint());
            Assert.assertEquals(r2OpsBefore + 1, r2.shard.stats().translogNumOps());
            Assert.assertEquals(gap.seqNo(), r2.shard.get("doc2").seqNo());
            Assert.assertEquals(gap2.seqNo(), r2.shard.get("doc3").seqNo());
            Assert.assertEquals(2L, group.checkpointTracker().getLocalCheckpoint("alloc-R2"));

            IndexResult after = group.replicateIndex(IndexOperation.of("doc4", Map.of("title", "four")), WaitForActiveShards.ALL);
            Assert.assertEquals(3L, after.seqNo());
            assertSameSeqNoHistory(r1.shard, r2.shard);
            Assert.assertEquals(3L, r2.shard.get("doc4").seqNo());
        } finally {
            primary.close();
            r1.close();
            r2.close();
        }
    }
}
