package com.naqqa.elasticsearch.monitor.stats;

import com.naqqa.elasticsearch.monitor.stats.fake.FakeCircuitBreakerStatsSource;
import com.naqqa.elasticsearch.monitor.stats.fake.FakeFieldDataStatsSource;
import com.naqqa.elasticsearch.monitor.stats.fake.FakeGetStatsSource;
import com.naqqa.elasticsearch.monitor.stats.fake.FakeHttpStatsSource;
import com.naqqa.elasticsearch.monitor.stats.fake.FakeIndexingStatsSource;
import com.naqqa.elasticsearch.monitor.stats.fake.FakeMergeStatsSource;
import com.naqqa.elasticsearch.monitor.stats.fake.FakeQueryCacheStatsSource;
import com.naqqa.elasticsearch.monitor.stats.fake.FakeRefreshFlushStatsSource;
import com.naqqa.elasticsearch.monitor.stats.fake.FakeRequestCacheStatsSource;
import com.naqqa.elasticsearch.monitor.stats.fake.FakeSearchStatsSource;
import com.naqqa.elasticsearch.monitor.stats.fake.FakeSegmentsStatsSource;
import com.naqqa.elasticsearch.monitor.stats.fake.FakeThreadPoolStatsSource;
import com.naqqa.elasticsearch.monitor.stats.fake.FakeTransportStatsSource;
import com.naqqa.elasticsearch.monitor.stats.fake.FakeTranslogStatsSource;
import com.naqqa.elasticsearch.test.Test;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class NodeStatsCollectorTest {

    @Test
    public void assemblesNodeStatsMapFromFakeSources() {
        FakeIndexingStatsSource indexing = new FakeIndexingStatsSource();
        indexing.indexTotal = 100;
        indexing.indexTimeInMillis = 500;
        indexing.indexCurrent = 2;
        indexing.indexFailed = 1;
        indexing.deleteTotal = 10;

        FakeSearchStatsSource search = new FakeSearchStatsSource();
        search.queryTotal = 50;
        search.queryTimeInMillis = 250;
        search.openContexts = 3;

        FakeGetStatsSource get = new FakeGetStatsSource();
        get.total = 20;

        FakeMergeStatsSource merges = new FakeMergeStatsSource();
        merges.total = 5;
        merges.totalDocs = 1000;

        FakeRefreshFlushStatsSource refreshFlush = new FakeRefreshFlushStatsSource();
        refreshFlush.refreshTotal = 30;
        refreshFlush.flushTotal = 7;

        FakeQueryCacheStatsSource queryCache = new FakeQueryCacheStatsSource();
        queryCache.hitCount = 40;
        queryCache.missCount = 4;

        FakeRequestCacheStatsSource requestCache = new FakeRequestCacheStatsSource();
        requestCache.hitCount = 15;

        FakeFieldDataStatsSource fieldData = new FakeFieldDataStatsSource();
        fieldData.memorySizeInBytes = 2048;

        FakeSegmentsStatsSource segments = new FakeSegmentsStatsSource();
        segments.count = 12;
        segments.memoryInBytes = 4096;

        FakeTranslogStatsSource translog = new FakeTranslogStatsSource();
        translog.operations = 9;
        translog.sizeInBytes = 1024;

        FakeThreadPoolStatsSource threadPool = new FakeThreadPoolStatsSource();
        threadPool.entries.add(new ThreadPoolStatsSource.ThreadPoolEntry("search", 4, 2, 1, 0, 100, 4));

        FakeTransportStatsSource transport = new FakeTransportStatsSource();
        transport.rxCount = 200;
        transport.txCount = 190;

        FakeHttpStatsSource http = new FakeHttpStatsSource();
        http.currentOpen = 3;
        http.totalOpened = 50;

        FakeCircuitBreakerStatsSource breakers = new FakeCircuitBreakerStatsSource();
        breakers.entries.add(new CircuitBreakerStatsSource.BreakerEntry("request", 1000, 100, 1.0, 0));

        NodeStatsCollector collector = new NodeStatsCollector("node-1", "node-one", System.currentTimeMillis(),
                List.of(Path.of(".")), indexing, search, get, merges, refreshFlush, queryCache, requestCache,
                fieldData, segments, translog, threadPool, transport, http, breakers);

        NodeStats stats = collector.collect();
        Map<String, Object> map = stats.toMap();

        assertEquals("node-1", map.get("node_id"));

        @SuppressWarnings("unchecked")
        Map<String, Object> indices = (Map<String, Object>) map.get("indices");
        @SuppressWarnings("unchecked")
        Map<String, Object> indexingMap = (Map<String, Object>) indices.get("indexing");
        assertEquals(100L, indexingMap.get("index_total"));
        assertEquals(1L, indexingMap.get("index_failed"));

        @SuppressWarnings("unchecked")
        Map<String, Object> segmentsMap = (Map<String, Object>) indices.get("segments");
        assertEquals(12L, segmentsMap.get("count"));

        @SuppressWarnings("unchecked")
        Map<String, Object> threadPoolMap = (Map<String, Object>) map.get("thread_pool");
        assertTrue(threadPoolMap.containsKey("search"));

        @SuppressWarnings("unchecked")
        Map<String, Object> breakersMap = (Map<String, Object>) map.get("breakers");
        assertTrue(breakersMap.containsKey("request"));

        assertTrue(map.containsKey("jvm"));
        assertTrue(map.containsKey("os"));
        assertTrue(map.containsKey("process"));
        assertTrue(map.containsKey("fs"));
        assertTrue(map.containsKey("transport"));
        assertTrue(map.containsKey("http"));
    }
}
