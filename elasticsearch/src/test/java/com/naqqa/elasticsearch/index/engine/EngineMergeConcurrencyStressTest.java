package com.naqqa.elasticsearch.index.engine;

import com.naqqa.elasticsearch.common.settings.Settings;
import com.naqqa.elasticsearch.common.unit.ByteSizeValue;
import com.naqqa.elasticsearch.common.unit.TimeValue;
import com.naqqa.elasticsearch.index.mapper.MapperService;
import com.naqqa.elasticsearch.index.shard.IndexShard;
import com.naqqa.elasticsearch.index.translog.Durability;
import com.naqqa.elasticsearch.index.translog.TranslogConfig;
import com.naqqa.elasticsearch.store.Directory;
import com.naqqa.elasticsearch.store.FSDirectory;
import com.naqqa.elasticsearch.test.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class EngineMergeConcurrencyStressTest {

    private static final long DURATION_MILLIS = 20_000;

    @Test
    public void concurrentIndexingRefreshFlushMergeAndSearchProducesNoRefCountErrorsOrLostDocs() throws Exception {
        Path shardPath = EngineTestSupport.newTempShardPath();
        MapperService mapperService = EngineTestSupport.newMapperService();
        Directory directory = new FSDirectory(shardPath.resolve("index"));
        TranslogConfig translogConfig = TranslogConfig.defaultConfig(shardPath.resolve("translog"))
            .withDurability(Durability.ASYNC);
        EngineConfig config = new EngineConfig(shardPath, directory, mapperService, translogConfig,
            TimeValue.MINUS_ONE, ByteSizeValue.ofMb(64), 1L, 3, 3, 0.3, null);
        IndexShard shard = IndexShard.open(config, mapperService);
        InternalEngine engine = (InternalEngine) shard.engine();

        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicInteger nextId = new AtomicInteger(0);
        AtomicInteger indexed = new AtomicInteger(0);
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        List<Thread> threads = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            threads.add(startLoop("indexer-" + i, stop, errors, () -> {
                int id = nextId.getAndIncrement();
                shard.index("doc-" + id, Map.of("title", "value " + id));
                indexed.incrementAndGet();
            }));
        }
        threads.add(startLoop("flusher", stop, errors, () -> {
            engine.writeIndexingBufferToSegment();
            Thread.sleep(15);
        }));
        threads.add(startLoop("refresher", stop, errors, () -> {
            shard.refresh();
            Thread.sleep(40);
        }));
        threads.add(startLoop("merger", stop, errors, () -> {
            shard.forceMerge(3);
            Thread.sleep(120);
        }));
        for (int i = 0; i < 2; i++) {
            threads.add(startLoop("searcher-" + i, stop, errors, () -> {
                try (EngineSearcher searcher = shard.acquireSearcher()) {
                    assertTrue(searcher.numDocs() >= 0, "numDocs should never be negative");
                }
                Thread.sleep(5);
            }));
        }

        Thread.sleep(DURATION_MILLIS);
        stop.set(true);
        for (Thread t : threads) {
            t.join(10_000);
        }

        assertTrue(errors.isEmpty(), "unexpected errors during concurrent merge/refresh/flush/search stress: " + errors);

        shard.refresh();
        shard.forceMerge(1);
        assertEquals(indexed.get(), shard.docCount());
        assertEquals(1, shard.segmentCount());

        shard.flush(true);
        shard.close();

        IndexShard reopened = IndexShard.open(config, mapperService);
        try {
            assertEquals(indexed.get(), reopened.docCount());
        } finally {
            reopened.close();
        }
    }

    private interface Op {
        void run() throws Exception;
    }

    private static Thread startLoop(String name, AtomicBoolean stop, List<Throwable> errors, Op op) {
        Thread t = new Thread(() -> {
            while (!stop.get()) {
                try {
                    op.run();
                } catch (Throwable e) {
                    errors.add(e);
                    return;
                }
            }
        }, "stress-" + name);
        t.setDaemon(true);
        t.start();
        return t;
    }
}
