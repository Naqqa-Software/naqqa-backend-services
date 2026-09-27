package com.naqqa.elasticsearch.index.engine;

import com.naqqa.elasticsearch.common.json.JsonValue;
import com.naqqa.elasticsearch.index.mapper.MapperService;
import com.naqqa.elasticsearch.index.seqno.SequenceNumbers;
import com.naqqa.elasticsearch.index.translog.VersionType;
import com.naqqa.elasticsearch.test.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertFalse;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class ExplicitSeqNoEngineTest {

    private static IndexOperation replicaIndex(String id, String title, long version) {
        return new IndexOperation(id, null, Map.of("title", title), version, VersionType.EXTERNAL,
            SequenceNumbers.UNASSIGNED_SEQ_NO, SequenceNumbers.UNASSIGNED_PRIMARY_TERM);
    }

    private static DeleteOperation replicaDelete(String id, long version) {
        return new DeleteOperation(id, version, VersionType.EXTERNAL,
            SequenceNumbers.UNASSIGNED_SEQ_NO, SequenceNumbers.UNASSIGNED_PRIMARY_TERM);
    }

    @SuppressWarnings("unchecked")
    private static String title(GetResult result) {
        return (String) ((Map<String, Object>) JsonValue.parse(result.source().toBytesArray()).toJava()).get("title");
    }

    @Test
    public void explicitSeqNoIsUsedVerbatimAndOutOfOrderGapsFill() throws IOException {
        Path shardPath = EngineTestSupport.newTempShardPath();
        MapperService mapperService = EngineTestSupport.newMapperService();
        InternalEngine engine = EngineTestSupport.open(shardPath, mapperService);
        try {
            IndexResult r2 = engine.indexAtSeqNo(replicaIndex("c", "three", 1), 2, 1);
            assertTrue(r2.success());
            assertEquals(2L, r2.seqNo());
            assertEquals(SequenceNumbers.NO_OPS_PERFORMED, engine.localCheckpoint());
            assertEquals(2L, engine.maxSeqNo());
            assertFalse(engine.hasProcessedSeqNo(0));
            assertFalse(engine.hasProcessedSeqNo(1));
            assertTrue(engine.hasProcessedSeqNo(2));

            assertTrue(engine.indexAtSeqNo(replicaIndex("a", "one", 1), 0, 1).success());
            assertEquals(0L, engine.localCheckpoint());

            assertTrue(engine.indexAtSeqNo(replicaIndex("b", "two", 1), 1, 1).success());
            assertEquals(2L, engine.localCheckpoint());
            assertEquals(2L, engine.maxSeqNo());

            assertEquals(0L, engine.get("a").seqNo());
            assertEquals(1L, engine.get("b").seqNo());
            assertEquals(2L, engine.get("c").seqNo());

            IndexResult primaryPath = engine.index(IndexOperation.of("d", Map.of("title", "four")));
            assertEquals(3L, primaryPath.seqNo());
            assertEquals(3L, engine.localCheckpoint());
        } finally {
            engine.close();
        }
    }

    @Test
    public void duplicateSeqNoIsIdempotentNoOp() throws IOException {
        Path shardPath = EngineTestSupport.newTempShardPath();
        MapperService mapperService = EngineTestSupport.newMapperService();
        InternalEngine engine = EngineTestSupport.open(shardPath, mapperService);
        try {
            IndexResult first = engine.indexAtSeqNo(replicaIndex("a", "original", 1), 0, 1);
            assertTrue(first.success());
            assertTrue(first.created());
            long translogOps = engine.stats().translogNumOps();

            IndexResult dup = engine.indexAtSeqNo(replicaIndex("a", "should-not-apply", 1), 0, 1);
            assertTrue(dup.success());
            assertFalse(dup.created());
            assertEquals(0L, dup.seqNo());
            assertEquals("original", title(engine.get("a")));
            assertEquals(1L, engine.get("a").version());
            assertEquals(translogOps, engine.stats().translogNumOps());

            DeleteResult dupDelete = engine.deleteAtSeqNo(replicaDelete("a", 2), 0, 1);
            assertTrue(dupDelete.success());
            assertTrue(engine.get("a").exists());

            NoOpResult dupNoOp = engine.noOpAtSeqNo(new NoOpOperation("dup"), 0, 1);
            assertTrue(dupNoOp.success());
            assertEquals(translogOps, engine.stats().translogNumOps());
            assertEquals(0L, engine.localCheckpoint());
            assertEquals(0L, engine.maxSeqNo());
        } finally {
            engine.close();
        }
    }

    @Test
    public void staleOpArrivingAfterNewerOpDoesNotOverwriteButIsMarkedProcessed() throws IOException {
        Path shardPath = EngineTestSupport.newTempShardPath();
        MapperService mapperService = EngineTestSupport.newMapperService();
        InternalEngine engine = EngineTestSupport.open(shardPath, mapperService);
        try {
            assertTrue(engine.indexAtSeqNo(replicaIndex("a", "newer", 2), 1, 1).success());
            IndexResult stale = engine.indexAtSeqNo(replicaIndex("a", "older", 1), 0, 1);
            assertTrue(stale.success());
            assertFalse(stale.created());
            GetResult got = engine.get("a");
            assertEquals("newer", title(got));
            assertEquals(1L, got.seqNo());
            assertEquals(2L, got.version());
            assertEquals(1L, engine.localCheckpoint());

            assertTrue(engine.deleteAtSeqNo(replicaDelete("b", 2), 3, 1).success());
            assertTrue(engine.indexAtSeqNo(replicaIndex("b", "resurrected?", 1), 2, 1).success());
            assertFalse(engine.get("b").exists());
            assertEquals(3L, engine.localCheckpoint());
        } finally {
            engine.close();
        }
    }

    @Test
    public void deleteAndNoOpAtSeqNoSurviveRestartWithExactCheckpoint() throws IOException {
        Path shardPath = EngineTestSupport.newTempShardPath();
        MapperService mapperService = EngineTestSupport.newMapperService();
        InternalEngine engine = EngineTestSupport.open(shardPath, mapperService);
        try {
            assertTrue(engine.indexAtSeqNo(replicaIndex("a", "one", 1), 0, 1).success());
            assertTrue(engine.indexAtSeqNo(replicaIndex("b", "two", 1), 1, 1).success());
            engine.flush(true);
            DeleteResult del = engine.deleteAtSeqNo(replicaDelete("a", 2), 4, 2);
            assertTrue(del.success());
            assertTrue(del.found());
            assertEquals(4L, del.seqNo());
            assertEquals(2L, del.primaryTerm());
            assertTrue(engine.noOpAtSeqNo(new NoOpOperation("gap"), 2, 2).success());
            assertEquals(2L, engine.localCheckpoint());
            assertEquals(4L, engine.maxSeqNo());
        } finally {
            engine.close();
        }

        InternalEngine reopened = EngineTestSupport.open(shardPath, mapperService);
        try {
            assertEquals(2L, reopened.localCheckpoint());
            assertEquals(4L, reopened.maxSeqNo());
            assertFalse(reopened.hasProcessedSeqNo(3));
            assertTrue(reopened.hasProcessedSeqNo(4));
            assertFalse(reopened.get("a").exists());
            assertTrue(reopened.get("b").exists());
            assertTrue(reopened.indexAtSeqNo(replicaIndex("c", "three", 1), 3, 2).success());
            assertEquals(4L, reopened.localCheckpoint());
        } finally {
            reopened.close();
        }
    }

    @Test
    public void invalidExplicitSeqNoOrTermIsRejected() throws IOException {
        Path shardPath = EngineTestSupport.newTempShardPath();
        MapperService mapperService = EngineTestSupport.newMapperService();
        InternalEngine engine = EngineTestSupport.open(shardPath, mapperService);
        try {
            assertFalse(engine.indexAtSeqNo(replicaIndex("a", "x", 1), -1, 1).success());
            assertFalse(engine.indexAtSeqNo(replicaIndex("a", "x", 1), 0, 0).success());
            assertFalse(engine.deleteAtSeqNo(replicaDelete("a", 1), SequenceNumbers.UNASSIGNED_SEQ_NO, 1).success());
            assertFalse(engine.noOpAtSeqNo(new NoOpOperation("x"), -5, 1).success());
            assertEquals(SequenceNumbers.NO_OPS_PERFORMED, engine.maxSeqNo());
        } finally {
            engine.close();
        }
    }
}
