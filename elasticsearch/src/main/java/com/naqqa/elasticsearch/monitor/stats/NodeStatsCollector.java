package com.naqqa.elasticsearch.monitor.stats;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class NodeStatsCollector {

    private final String nodeId;
    private final String nodeName;
    private final long startTimeMillis;
    private final List<Path> dataPaths;

    private final IndexingStatsSource indexingStatsSource;
    private final SearchStatsSource searchStatsSource;
    private final GetStatsSource getStatsSource;
    private final MergeStatsSource mergeStatsSource;
    private final RefreshFlushStatsSource refreshFlushStatsSource;
    private final QueryCacheStatsSource queryCacheStatsSource;
    private final RequestCacheStatsSource requestCacheStatsSource;
    private final FieldDataStatsSource fieldDataStatsSource;
    private final SegmentsStatsSource segmentsStatsSource;
    private final TranslogStatsSource translogStatsSource;
    private final ThreadPoolStatsSource threadPoolStatsSource;
    private final TransportStatsSource transportStatsSource;
    private final HttpStatsSource httpStatsSource;
    private final CircuitBreakerStatsSource circuitBreakerStatsSource;

    public NodeStatsCollector(String nodeId, String nodeName, long startTimeMillis, List<Path> dataPaths,
            IndexingStatsSource indexingStatsSource, SearchStatsSource searchStatsSource,
            GetStatsSource getStatsSource, MergeStatsSource mergeStatsSource,
            RefreshFlushStatsSource refreshFlushStatsSource, QueryCacheStatsSource queryCacheStatsSource,
            RequestCacheStatsSource requestCacheStatsSource, FieldDataStatsSource fieldDataStatsSource,
            SegmentsStatsSource segmentsStatsSource, TranslogStatsSource translogStatsSource,
            ThreadPoolStatsSource threadPoolStatsSource, TransportStatsSource transportStatsSource,
            HttpStatsSource httpStatsSource, CircuitBreakerStatsSource circuitBreakerStatsSource) {
        this.nodeId = nodeId;
        this.nodeName = nodeName;
        this.startTimeMillis = startTimeMillis;
        this.dataPaths = dataPaths == null || dataPaths.isEmpty() ? List.of(Path.of(".")) : dataPaths;
        this.indexingStatsSource = indexingStatsSource;
        this.searchStatsSource = searchStatsSource;
        this.getStatsSource = getStatsSource;
        this.mergeStatsSource = mergeStatsSource;
        this.refreshFlushStatsSource = refreshFlushStatsSource;
        this.queryCacheStatsSource = queryCacheStatsSource;
        this.requestCacheStatsSource = requestCacheStatsSource;
        this.fieldDataStatsSource = fieldDataStatsSource;
        this.segmentsStatsSource = segmentsStatsSource;
        this.translogStatsSource = translogStatsSource;
        this.threadPoolStatsSource = threadPoolStatsSource;
        this.transportStatsSource = transportStatsSource;
        this.httpStatsSource = httpStatsSource;
        this.circuitBreakerStatsSource = circuitBreakerStatsSource;
    }

    public NodeStats collect() {
        long now = System.currentTimeMillis();
        CommonStats indices = CommonStats.fromSources(indexingStatsSource, searchStatsSource, getStatsSource,
                mergeStatsSource, refreshFlushStatsSource, queryCacheStatsSource, requestCacheStatsSource,
                fieldDataStatsSource, segmentsStatsSource, translogStatsSource);
        JvmStats jvm = JvmStats.capture(Math.max(0, now - startTimeMillis));
        OsStats os = OsStats.capture();
        ProcessStats process = ProcessStats.capture();
        FsStats fs = FsStats.capture(dataPaths);
        List<ThreadPoolStatsSource.ThreadPoolEntry> threadPools = new ArrayList<>(threadPoolStatsSource.getThreadPools());
        NodeStats.Transport transport = NodeStats.Transport.from(transportStatsSource);
        NodeStats.Http http = NodeStats.Http.from(httpStatsSource);
        List<CircuitBreakerStatsSource.BreakerEntry> breakers = NodeStats.copyBreakers(circuitBreakerStatsSource);
        return new NodeStats(nodeId, nodeName, now, indices, jvm, os, process, fs, threadPools, transport, http,
                breakers);
    }
}
