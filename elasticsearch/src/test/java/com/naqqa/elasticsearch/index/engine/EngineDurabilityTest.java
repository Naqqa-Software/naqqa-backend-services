package com.naqqa.elasticsearch.index.engine;

import com.naqqa.elasticsearch.codec.segment.SegmentInfos;
import com.naqqa.elasticsearch.index.mapper.MapperService;
import com.naqqa.elasticsearch.index.engine.segment.SegmentReader;
import com.naqqa.elasticsearch.index.translog.Releasable;
import com.naqqa.elasticsearch.store.Directory;
import com.naqqa.elasticsearch.test.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertFalse;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class EngineDurabilityTest {

    @Test
    public void mergeThenCrashWithoutFlushKeepsFlushedDocsDurable() throws IOException {
        Path shardPath = EngineTestSupport.newTempShardPath();
        MapperService mapperService = EngineTestSupport.newMapperService();
        InternalEngine engine = EngineTestSupport.open(shardPath, mapperService);
        engine.index(IndexOperation.of("1", Map.of("title", "alpha", "tag", "x")));
        engine.refresh("t");
        engine.index(IndexOperation.of("2", Map.of("title", "beta", "tag", "y")));
        engine.refresh("t");
        engine.index(IndexOperation.of("3", Map.of("title", "gamma", "tag", "z")));
        engine.refresh("t");
        FlushResult flushed = engine.flush(true);
        assertTrue(flushed.flushed());

        engine.forceMerge(1);
        assertEquals(1, engine.stats().segmentCount());

        engine.close();

        InternalEngine reopened = EngineTestSupport.open(shardPath, mapperService);
        try {
            assertTrue(reopened.get("1").exists());
            assertTrue(reopened.get("2").exists());
            assertTrue(reopened.get("3").exists());
            try (EngineSearcher searcher = reopened.acquireSearcher()) {
                assertEquals(3, searcher.numDocs());
            }
        } finally {
            reopened.close();
        }
    }

    @Test
    public void repeatedFlushesAndMergesLeaveOnlyOneCommitAndNoOrphans() throws IOException {
        Path shardPath = EngineTestSupport.newTempShardPath();
        MapperService mapperService = EngineTestSupport.newMapperService();
        InternalEngine engine = EngineTestSupport.open(shardPath, mapperService);
        try {
            for (int i = 0; i < 6; i++) {
                engine.index(IndexOperation.of("d" + i, Map.of("title", "doc " + i, "tag", "t" + (i % 2))));
                engine.refresh("t");
                engine.flush(true);
                if (i % 2 == 1) {
                    engine.forceMerge(1);
                    engine.flush(true);
                }
            }
            Directory directory = engine.config().directory();

            int commitFileCount = 0;
            for (String name : directory.listAll()) {
                if (name.startsWith(SegmentInfos.COMMIT_PREFIX)) {
                    commitFileCount++;
                }
                assertFalse(name.startsWith(SegmentInfos.PENDING_PREFIX), "leftover pending commit file: " + name);
            }
            assertEquals(1, commitFileCount);

            Set<String> expected = new HashSet<>();
            SegmentInfos latest = SegmentInfos.readLatestCommit(directory);
            expected.add(SegmentInfos.fileNameForGeneration(latest.generation()));
            try (EngineSearcher searcher = engine.acquireSearcher()) {
                for (SegmentReader sr : searcher.leaves()) {
                    expected.addAll(sr.allFiles());
                }
            }

            for (String name : directory.listAll()) {
                if (name.equals("write.lock")) {
                    continue;
                }
                assertTrue(expected.contains(name), "orphan file left on disk: " + name);
            }
        } finally {
            engine.close();
        }
    }

    @Test
    public void heldCommitKeepsSupersededFilesUntilReleased() throws IOException {
        Path shardPath = EngineTestSupport.newTempShardPath();
        MapperService mapperService = EngineTestSupport.newMapperService();
        InternalEngine engine = EngineTestSupport.open(shardPath, mapperService);
        try {
            engine.index(IndexOperation.of("1", Map.of("title", "alpha", "tag", "x")));
            engine.refresh("t");
            engine.index(IndexOperation.of("2", Map.of("title", "beta", "tag", "y")));
            engine.refresh("t");
            engine.flush(true);

            Directory directory = engine.config().directory();
            Set<String> preMergeFiles = new HashSet<>();
            try (EngineSearcher searcher = engine.acquireSearcher()) {
                for (SegmentReader sr : searcher.leaves()) {
                    preMergeFiles.addAll(sr.staticFiles());
                }
            }

            Releasable held = engine.acquireLastCommitRef();
            try {
                engine.forceMerge(1);
                engine.flush(true);

                for (String f : preMergeFiles) {
                    assertTrue(directory.fileExists(f), "held commit file deleted too early: " + f);
                }
            } finally {
                held.close();
            }

            boolean anyRemaining = false;
            for (String f : preMergeFiles) {
                if (directory.fileExists(f)) {
                    anyRemaining = true;
                }
            }
            assertFalse(anyRemaining, "superseded files were not cleaned up after release");
        } finally {
            engine.close();
        }
    }
}
