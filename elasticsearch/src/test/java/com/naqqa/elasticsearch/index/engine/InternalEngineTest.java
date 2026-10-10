package com.naqqa.elasticsearch.index.engine;

import com.naqqa.elasticsearch.index.engine.segment.SegmentReader;
import com.naqqa.elasticsearch.index.engine.segment.SegmentWriter;
import com.naqqa.elasticsearch.index.mapper.MapperService;
import com.naqqa.elasticsearch.test.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertFalse;
import static com.naqqa.elasticsearch.test.Assert.assertNull;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class InternalEngineTest {

    @Test
    public void indexRefreshMakesDocsVisibleNotBefore() throws IOException {
        Path shardPath = EngineTestSupport.newTempShardPath();
        MapperService mapperService = EngineTestSupport.newMapperService();
        InternalEngine engine = EngineTestSupport.open(shardPath, mapperService);
        try {
            engine.index(IndexOperation.of("1", Map.of("title", "hello world", "tag", "a")));

            try (EngineSearcher searcher = engine.acquireSearcher()) {
                assertEquals(0, searcher.numDocs());
            }

            RefreshResult r = engine.refresh("test");
            assertTrue(r.refreshed());

            try (EngineSearcher searcher = engine.acquireSearcher()) {
                assertEquals(1, searcher.numDocs());
                boolean found = false;
                for (SegmentReader sr : searcher.leaves()) {
                    if (sr.findLiveDocForId(SegmentWriter.ID_FIELD, "1") != null) {
                        found = true;
                    }
                }
                assertTrue(found, "doc should be found in a segment after refresh");
            }
        } finally {
            engine.close();
        }
    }

    @Test
    public void realtimeGetBeforeRefresh() throws IOException {
        Path shardPath = EngineTestSupport.newTempShardPath();
        MapperService mapperService = EngineTestSupport.newMapperService();
        InternalEngine engine = EngineTestSupport.open(shardPath, mapperService);
        try {
            engine.index(IndexOperation.of("1", Map.of("title", "hello world", "tag", "a")));

            GetResult get = engine.get("1");
            assertTrue(get.exists());
            assertEquals("1", get.id());
            assertTrue(get.source().utf8ToString().contains("hello"));

            try (EngineSearcher searcher = engine.acquireSearcher()) {
                assertEquals(0, searcher.numDocs());
            }

            GetResult missing = engine.get("does-not-exist");
            assertFalse(missing.exists());
        } finally {
            engine.close();
        }
    }

    @Test
    public void flushPersistsCommitSurvivingCloseAndReopenWithoutTranslogReplay() throws IOException {
        Path shardPath = EngineTestSupport.newTempShardPath();
        MapperService mapperService = EngineTestSupport.newMapperService();
        InternalEngine engine = EngineTestSupport.open(shardPath, mapperService);
        engine.index(IndexOperation.of("1", Map.of("title", "alpha", "tag", "x")));
        engine.index(IndexOperation.of("2", Map.of("title", "beta", "tag", "y")));
        FlushResult flushResult = engine.flush(true);
        assertTrue(flushResult.flushed());
        engine.close();

        InternalEngine reopened = EngineTestSupport.open(shardPath, mapperService);
        try {
            EngineStats stats = reopened.stats();
            assertEquals(0L, stats.translogNumOps());
            assertEquals(2, stats.numDocs());

            GetResult r1 = reopened.get("1");
            assertTrue(r1.exists());
            GetResult r2 = reopened.get("2");
            assertTrue(r2.exists());

            try (EngineSearcher searcher = reopened.acquireSearcher()) {
                assertEquals(2, searcher.numDocs());
            }
        } finally {
            reopened.close();
        }
    }

    @Test
    public void crashRecoveryReplaysUnflushedOpsFromTranslog() throws IOException {
        Path shardPath = EngineTestSupport.newTempShardPath();
        MapperService mapperService = EngineTestSupport.newMapperService();
        InternalEngine engine = EngineTestSupport.open(shardPath, mapperService);
        engine.index(IndexOperation.of("1", Map.of("title", "alpha", "tag", "x")));
        engine.index(IndexOperation.of("2", Map.of("title", "beta", "tag", "y")));
        engine.close();

        InternalEngine restarted = EngineTestSupport.open(shardPath, mapperService);
        try {
            GetResult r1 = restarted.get("1");
            GetResult r2 = restarted.get("2");
            assertTrue(r1.exists(), "doc 1 should have been recovered from translog");
            assertTrue(r2.exists(), "doc 2 should have been recovered from translog");
            assertEquals("1", r1.id());

            restarted.refresh("test");
            try (EngineSearcher searcher = restarted.acquireSearcher()) {
                assertEquals(2, searcher.numDocs());
            }
        } finally {
            restarted.close();
        }
    }

    @Test
    public void deleteRemovesDocFromGetAndSubsequentSearch() throws IOException {
        Path shardPath = EngineTestSupport.newTempShardPath();
        MapperService mapperService = EngineTestSupport.newMapperService();
        InternalEngine engine = EngineTestSupport.open(shardPath, mapperService);
        try {
            engine.index(IndexOperation.of("1", Map.of("title", "alpha", "tag", "x")));
            engine.refresh("t1");

            try (EngineSearcher searcher = engine.acquireSearcher()) {
                assertEquals(1, searcher.numDocs());
            }

            DeleteResult del = engine.delete(DeleteOperation.of("1"));
            assertTrue(del.found());

            GetResult get = engine.get("1");
            assertFalse(get.exists());

            engine.refresh("t2");
            try (EngineSearcher searcher = engine.acquireSearcher()) {
                assertEquals(0, searcher.numDocs());
                for (SegmentReader sr : searcher.leaves()) {
                    assertNull(sr.findLiveDocForId(SegmentWriter.ID_FIELD, "1"));
                }
            }
        } finally {
            engine.close();
        }
    }

    @Test
    public void mergeReducesSegmentCountAndReclaimsDeletedDocs() throws IOException {
        Path shardPath = EngineTestSupport.newTempShardPath();
        MapperService mapperService = EngineTestSupport.newMapperService();
        InternalEngine engine = EngineTestSupport.open(shardPath, mapperService);
        try {
            for (int i = 0; i < 5; i++) {
                engine.index(IndexOperation.of("doc" + i, Map.of("title", "t" + i, "tag", "x")));
                engine.refresh("r" + i);
            }
            EngineStats before = engine.stats();
            assertEquals(5, before.segmentCount());
            assertEquals(5, before.numDocs());

            engine.delete(DeleteOperation.of("doc0"));
            engine.delete(DeleteOperation.of("doc1"));
            engine.refresh("after-delete");

            EngineStats afterDelete = engine.stats();
            assertEquals(3, afterDelete.numDocs());
            assertEquals(2, afterDelete.numDeletedDocs());

            MergeResult mr = engine.forceMerge(1);
            assertEquals(1, mr.segmentCountAfter());

            EngineStats afterMerge = engine.stats();
            assertEquals(1, afterMerge.segmentCount());
            assertEquals(3, afterMerge.numDocs());
            assertEquals(0, afterMerge.numDeletedDocs());

            assertFalse(engine.get("doc0").exists());
            assertTrue(engine.get("doc2").exists());
        } finally {
            engine.close();
        }
    }

    @Test
    public void concurrentIndexingAndRefreshLoopIsCorrupionFreeAndEventuallyVisible() throws Exception {
        Path shardPath = EngineTestSupport.newTempShardPath();
        MapperService mapperService = EngineTestSupport.newMapperService();
        InternalEngine engine = EngineTestSupport.open(shardPath, mapperService);
        try {
            int numDocs = 150;
            ExecutorService pool = Executors.newFixedThreadPool(4);
            AtomicBoolean stop = new AtomicBoolean(false);

            Future<?> refresher = pool.submit(() -> {
                while (!stop.get()) {
                    try {
                        engine.refresh("loop");
                        Thread.sleep(5);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    } catch (InterruptedException ignored) {
                    }
                }
            });

            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < numDocs; i++) {
                int id = i;
                futures.add(pool.submit(() -> {
                    try {
                        engine.index(IndexOperation.of("doc" + id, Map.of("title", "t" + id, "tag", "x")));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
            stop.set(true);
            refresher.get();
            pool.shutdown();

            engine.refresh("final");

            try (EngineSearcher searcher = engine.acquireSearcher()) {
                assertEquals(numDocs, searcher.numDocs());
            }
            for (int i = 0; i < numDocs; i++) {
                assertTrue(engine.get("doc" + i).exists(), "doc" + i + " should exist");
            }
        } finally {
            engine.close();
        }
    }

    private static long tlogFileCount(Path shardPath) throws IOException {
        try (java.util.stream.Stream<Path> files = java.nio.file.Files.list(shardPath.resolve("translog"))) {
            return files.filter(f -> f.getFileName().toString().endsWith(".tlog")).count();
        }
    }

    @Test
    public void flushThresholdCountsAllTranslogGenerationsNotJustTheCurrentOne() throws IOException {
        Path shardPath = EngineTestSupport.newTempShardPath();
        MapperService mapperService = EngineTestSupport.newMapperService();
        com.naqqa.elasticsearch.index.translog.TranslogConfig tc = new com.naqqa.elasticsearch.index.translog.TranslogConfig(
            shardPath.resolve("translog"), com.naqqa.elasticsearch.common.unit.ByteSizeValue.ofBytes(2_000),
            com.naqqa.elasticsearch.common.unit.TimeValue.MINUS_ONE, com.naqqa.elasticsearch.index.translog.Durability.REQUEST,
            com.naqqa.elasticsearch.common.unit.TimeValue.timeValueSeconds(5), null);
        com.naqqa.elasticsearch.store.Directory dir = new com.naqqa.elasticsearch.store.FSDirectory(shardPath.resolve("index"));
        // generation size (2 KB) is far below the flush threshold (20 KB): the old per-generation check never fired.
        EngineConfig config = EngineConfig.defaultConfig(shardPath, dir, mapperService, tc)
            .withRefreshInterval(com.naqqa.elasticsearch.common.unit.TimeValue.MINUS_ONE)
            .withFlushThresholdSize(com.naqqa.elasticsearch.common.unit.ByteSizeValue.ofBytes(20_000));
        InternalEngine engine = InternalEngine.open(config);
        try {
            long maxFiles = 0;
            for (int i = 0; i < 400; i++) {
                engine.index(IndexOperation.of("doc" + i, Map.of("title", "some reasonably long title number " + i, "tag", "t")));
                maxFiles = Math.max(maxFiles, tlogFileCount(shardPath));
            }
            assertTrue(maxFiles < 30, "translog generations must be trimmed by size-based flush, max files seen: " + maxFiles);
            assertTrue(engine.stats().translogSizeInBytes() < 400_000L, "translog must not grow unbounded");
        } finally {
            engine.close();
        }
    }

    @Test
    public void periodicFlushCommitsIdleUncommittedOperationsAndTrimsTranslog() throws IOException {
        Path shardPath = EngineTestSupport.newTempShardPath();
        MapperService mapperService = EngineTestSupport.newMapperService();
        InternalEngine engine = EngineTestSupport.open(shardPath, mapperService);
        try {
            engine.index(IndexOperation.of("1", Map.of("title", "alpha", "tag", "x")));
            long now = System.currentTimeMillis();
            assertFalse(engine.maybeFlushPeriodically(now, 60_000L), "not due yet");
            assertTrue(engine.maybeFlushPeriodically(now + 120_000L, 60_000L), "due and has uncommitted ops");
            assertFalse(engine.maybeFlushPeriodically(now + 400_000L, 60_000L), "nothing new to commit");
        } finally {
            engine.close();
        }
        InternalEngine reopened = EngineTestSupport.open(shardPath, mapperService);
        try {
            assertEquals(0L, reopened.stats().translogNumOps());
            assertTrue(reopened.get("1").exists());
        } finally {
            reopened.close();
        }
    }

    @Test
    public void replayedTranslogIsCommittedOnOpen() throws IOException {
        Path shardPath = EngineTestSupport.newTempShardPath();
        MapperService mapperService = EngineTestSupport.newMapperService();
        InternalEngine engine = EngineTestSupport.open(shardPath, mapperService);
        engine.index(IndexOperation.of("1", Map.of("title", "alpha", "tag", "x")));
        engine.close();

        InternalEngine first = EngineTestSupport.open(shardPath, mapperService);
        first.close();
        InternalEngine second = EngineTestSupport.open(shardPath, mapperService);
        try {
            assertEquals(0L, second.stats().translogNumOps());
            assertTrue(second.get("1").exists());
        } finally {
            second.close();
        }
    }
}
