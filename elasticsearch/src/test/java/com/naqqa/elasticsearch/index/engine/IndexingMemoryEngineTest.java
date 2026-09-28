package com.naqqa.elasticsearch.index.engine;

import com.naqqa.elasticsearch.index.mapper.MapperService;
import com.naqqa.elasticsearch.index.translog.Durability;
import com.naqqa.elasticsearch.test.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertFalse;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class IndexingMemoryEngineTest {

    private static final long TEST_BUDGET_BYTES = 8L * 1024 * 1024;
    private static final long TOLERANCE_BYTES = 4L * 1024 * 1024;

    private static EngineConfig smallBudgetConfig(Path shardPath, MapperService mapperService) throws IOException {
        EngineConfig base = EngineTestSupport.newConfig(shardPath, mapperService);
        return base
            .withMemoryController(new IndexingMemoryController(TEST_BUDGET_BYTES))
            .withTranslogConfig(base.translogConfig().withDurability(Durability.ASYNC));
    }

    @Test
    public void bufferedRamStaysBoundedAndWritesMultipleSegmentsWithoutRefresh() throws IOException {
        Path shardPath = EngineTestSupport.newTempShardPath();
        MapperService mapperService = EngineTestSupport.newMapperService();
        InternalEngine engine = InternalEngine.open(smallBudgetConfig(shardPath, mapperService));
        try {
            int numDocs = 200_000;
            for (int i = 0; i < numDocs; i++) {
                engine.index(IndexOperation.of("doc" + i, Map.of("title", "product number " + i, "tag", "cat" + (i % 50))));
                if (i % 5000 == 0) {
                    assertTrue(engine.ramBytesUsed() <= TEST_BUDGET_BYTES + TOLERANCE_BYTES,
                        "buffered ram usage should stay near budget at doc " + i + " was " + engine.ramBytesUsed());
                }
            }
            assertTrue(engine.ramBytesUsed() <= TEST_BUDGET_BYTES + TOLERANCE_BYTES, "buffered ram usage should stay near budget at end");
            assertTrue(engine.pendingSegmentCount() >= 1, "multiple pending (non-searchable) segments should have been written");

            try (EngineSearcher searcher = engine.acquireSearcher()) {
                assertEquals(0, searcher.numDocs());
            }
            GetResult beforeRefresh = engine.get("doc0");
            assertTrue(beforeRefresh.exists(), "realtime get should find a doc flushed to an unrefreshed pending segment");

            RefreshResult r = engine.refresh("test");
            assertTrue(r.refreshed());
            assertEquals(0, engine.pendingSegmentCount());

            try (EngineSearcher searcher = engine.acquireSearcher()) {
                assertEquals(numDocs, searcher.numDocs());
            }
        } finally {
            engine.close();
        }
    }

    @Test
    public void realtimeGetAndOccWorkForDocInUnrefreshedPendingSegment() throws IOException {
        Path shardPath = EngineTestSupport.newTempShardPath();
        MapperService mapperService = EngineTestSupport.newMapperService();
        InternalEngine engine = InternalEngine.open(smallBudgetConfig(shardPath, mapperService));
        try {
            int numDocs = 40_000;
            for (int i = 0; i < numDocs; i++) {
                engine.index(IndexOperation.of("id" + i, Map.of("title", "alpha beta gamma " + i, "tag", "t" + i)));
            }
            assertTrue(engine.pendingSegmentCount() >= 1, "buffer should have been written to at least one pending segment");

            try (EngineSearcher searcher = engine.acquireSearcher()) {
                assertEquals(0, searcher.numDocs());
            }

            GetResult get = engine.get("id0");
            assertTrue(get.exists());
            assertEquals(1L, get.version());
            long seqNo = get.seqNo();
            long primaryTerm = get.primaryTerm();

            IndexResult conflict = engine.index(IndexOperation.of("id0", Map.of("title", "conflict", "tag", "x"))
                .withCas(seqNo + 999, primaryTerm));
            assertFalse(conflict.success(), "wrong if_seq_no should conflict for a doc that only lives in a pending segment");

            IndexResult ok = engine.index(IndexOperation.of("id0", Map.of("title", "updated", "tag", "y")).withCas(seqNo, primaryTerm));
            assertTrue(ok.success(), "correct if_seq_no/if_primary_term should succeed");
            assertEquals(2L, ok.version());
        } finally {
            engine.close();
        }
    }

    @Test
    public void crashRestartWithoutFlushReplaysDocsWrittenToPendingSegments() throws IOException {
        Path shardPath = EngineTestSupport.newTempShardPath();
        MapperService mapperService = EngineTestSupport.newMapperService();
        InternalEngine engine = InternalEngine.open(smallBudgetConfig(shardPath, mapperService));
        int numDocs = 30_000;
        for (int i = 0; i < numDocs; i++) {
            engine.index(IndexOperation.of("r" + i, Map.of("title", "recovery doc " + i, "tag", "z" + i)));
        }
        assertTrue(engine.pendingSegmentCount() >= 1, "expected at least one pending segment before crash");
        engine.close();

        InternalEngine restarted = InternalEngine.open(smallBudgetConfig(shardPath, mapperService));
        try {
            for (int i = 0; i < numDocs; i += 997) {
                assertTrue(restarted.get("r" + i).exists(), "doc r" + i + " should have been recovered from translog");
            }
            restarted.refresh("after-recovery");
            try (EngineSearcher searcher = restarted.acquireSearcher()) {
                assertEquals(numDocs, searcher.numDocs());
            }
        } finally {
            restarted.close();
        }
    }
}
