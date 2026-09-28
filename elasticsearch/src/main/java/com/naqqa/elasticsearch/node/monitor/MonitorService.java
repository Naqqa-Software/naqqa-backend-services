package com.naqqa.elasticsearch.node.monitor;

import com.naqqa.elasticsearch.common.breaker.CircuitBreaker;
import com.naqqa.elasticsearch.common.breaker.CircuitBreakerService;
import com.naqqa.elasticsearch.common.threadpool.ThreadPool;
import com.naqqa.elasticsearch.index.engine.EngineStats;
import com.naqqa.elasticsearch.index.shard.IndexShard;
import com.naqqa.elasticsearch.monitor.health.ClusterCoordinationHealthIndicator;
import com.naqqa.elasticsearch.monitor.health.DiskHealthIndicator;
import com.naqqa.elasticsearch.monitor.health.HealthReport;
import com.naqqa.elasticsearch.monitor.health.ShardsAvailabilityHealthIndicator;
import com.naqqa.elasticsearch.monitor.hotthreads.HotThreadsSampler;
import com.naqqa.elasticsearch.monitor.stats.CircuitBreakerStatsSource;
import com.naqqa.elasticsearch.monitor.stats.FieldDataStatsSource;
import com.naqqa.elasticsearch.monitor.stats.GetStatsSource;
import com.naqqa.elasticsearch.monitor.stats.HttpStatsSource;
import com.naqqa.elasticsearch.monitor.stats.IndexingStatsSource;
import com.naqqa.elasticsearch.monitor.stats.MergeStatsSource;
import com.naqqa.elasticsearch.monitor.stats.NodeStats;
import com.naqqa.elasticsearch.monitor.stats.NodeStatsCollector;
import com.naqqa.elasticsearch.monitor.stats.QueryCacheStatsSource;
import com.naqqa.elasticsearch.monitor.stats.RefreshFlushStatsSource;
import com.naqqa.elasticsearch.monitor.stats.RequestCacheStatsSource;
import com.naqqa.elasticsearch.monitor.stats.SearchStatsSource;
import com.naqqa.elasticsearch.monitor.stats.SegmentsStatsSource;
import com.naqqa.elasticsearch.monitor.stats.ThreadPoolStatsSource;
import com.naqqa.elasticsearch.monitor.stats.TranslogStatsSource;
import com.naqqa.elasticsearch.monitor.stats.TransportStatsSource;
import com.naqqa.elasticsearch.monitor.tasks.TaskManager;
import com.naqqa.elasticsearch.node.indices.IndexService;
import com.naqqa.elasticsearch.node.indices.IndicesService;
import com.naqqa.elasticsearch.transport.TransportService;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

public final class MonitorService {

    private final NodeStatsCollector collector;
    private final NodeCounters counters;
    private final IndicesService indicesService;
    private final ThreadPool threadPool;
    private final CircuitBreakerService breakerService;
    private final TaskManager taskManager;
    private final HotThreadsSampler hotThreadsSampler = new HotThreadsSampler();
    private final List<Path> dataPaths;
    private final long startTimeMillis;

    public MonitorService(String nodeId, String nodeName, long startTimeMillis, List<Path> dataPaths, NodeCounters counters,
                          IndicesService indicesService, ThreadPool threadPool, TransportService transportService,
                          CircuitBreakerService breakerService, TaskManager taskManager, IntSupplier openSearchContexts) {
        this.counters = counters;
        this.indicesService = indicesService;
        this.threadPool = threadPool;
        this.breakerService = breakerService;
        this.taskManager = taskManager;
        this.dataPaths = dataPaths;
        this.startTimeMillis = startTimeMillis;
        this.collector = new NodeStatsCollector(nodeId, nodeName, startTimeMillis, dataPaths,
            indexingSource(), searchSource(openSearchContexts), getSource(), mergeSource(), refreshFlushSource(),
            queryCacheSource(), requestCacheSource(), fieldDataSource(), segmentsSource(), translogSource(),
            threadPoolSource(), transportSource(transportService), httpSource(), breakerSource());
    }

    public NodeStats collect() {
        return collector.collect();
    }

    public NodeCounters counters() {
        return counters;
    }

    public TaskManager taskManager() {
        return taskManager;
    }

    public HotThreadsSampler hotThreadsSampler() {
        return hotThreadsSampler;
    }

    public long startTimeMillis() {
        return startTimeMillis;
    }

    public ThreadPool threadPool() {
        return threadPool;
    }

    public CircuitBreakerService breakerService() {
        return breakerService;
    }

    private List<EngineStats> engineStats() {
        List<EngineStats> out = new ArrayList<>();
        for (IndexService service : indicesService.indices().values()) {
            for (IndexShard shard : service.shards().values()) {
                try {
                    out.add(shard.stats());
                } catch (RuntimeException ignored) {
                }
            }
        }
        return out;
    }

    private IndexingStatsSource indexingSource() {
        return new IndexingStatsSource() {
            public long getIndexTotal() { return counters.indexing.total(); }
            public long getIndexTimeInMillis() { return counters.indexing.timeMillis(); }
            public long getIndexCurrent() { return counters.indexing.current(); }
            public long getIndexFailed() { return counters.indexing.failed(); }
            public long getDeleteTotal() { return counters.deletes.total(); }
            public long getDeleteTimeInMillis() { return counters.deletes.timeMillis(); }
            public long getDeleteCurrent() { return counters.deletes.current(); }
            public long getDeleteFailed() { return counters.deletes.failed(); }
        };
    }

    private SearchStatsSource searchSource(IntSupplier openContexts) {
        return new SearchStatsSource() {
            public long getQueryTotal() { return counters.query.total(); }
            public long getQueryTimeInMillis() { return counters.query.timeMillis(); }
            public long getQueryCurrent() { return counters.query.current(); }
            public long getFetchTotal() { return counters.fetch.total(); }
            public long getFetchTimeInMillis() { return counters.fetch.timeMillis(); }
            public long getFetchCurrent() { return counters.fetch.current(); }
            public long getScrollTotal() { return counters.scroll.total(); }
            public long getScrollTimeInMillis() { return counters.scroll.timeMillis(); }
            public long getScrollCurrent() { return counters.scroll.current(); }
            public long getSuggestTotal() { return counters.suggest.total(); }
            public long getSuggestTimeInMillis() { return counters.suggest.timeMillis(); }
            public long getSuggestCurrent() { return counters.suggest.current(); }
            public long getOpenContexts() { return openContexts.getAsInt(); }
        };
    }

    private GetStatsSource getSource() {
        return new GetStatsSource() {
            public long getTotal() { return counters.getExists.total() + counters.getMissing.total(); }
            public long getTimeInMillis() { return counters.getExists.timeMillis() + counters.getMissing.timeMillis(); }
            public long getExistsTotal() { return counters.getExists.total() - counters.getMissing.total(); }
            public long getExistsTimeInMillis() { return counters.getExists.timeMillis(); }
            public long getMissingTotal() { return counters.getMissing.total(); }
            public long getMissingTimeInMillis() { return counters.getMissing.timeMillis(); }
            public long getCurrent() { return counters.getExists.current(); }
        };
    }

    private MergeStatsSource mergeSource() {
        return new MergeStatsSource() {
            public long getTotal() { return counters.merge.total(); }
            public long getTotalTimeInMillis() { return counters.merge.timeMillis(); }
            public long getCurrent() { return counters.merge.current(); }
            public long getTotalDocs() { return counters.mergedDocs.sum(); }
            public long getTotalSizeInBytes() { return 0L; }
        };
    }

    private RefreshFlushStatsSource refreshFlushSource() {
        return new RefreshFlushStatsSource() {
            public long getRefreshTotal() { return counters.refresh.total(); }
            public long getRefreshTotalTimeInMillis() { return counters.refresh.timeMillis(); }
            public long getFlushTotal() { return counters.flush.total(); }
            public long getFlushTotalTimeInMillis() { return counters.flush.timeMillis(); }
        };
    }

    private static QueryCacheStatsSource queryCacheSource() {
        return new QueryCacheStatsSource() {
            private com.naqqa.elasticsearch.search.execution.QueryCache.Stats s() {
                return com.naqqa.elasticsearch.search.execution.QueryCaches.shared().stats();
            }
            public long getCacheSize() { return s().cacheSize(); }
            public long getCacheCount() { return s().cacheCount(); }
            public long getEvictions() { return s().evictions(); }
            public long getHitCount() { return s().hitCount(); }
            public long getMissCount() { return s().missCount(); }
            public long getMemorySizeInBytes() { return s().memorySizeInBytes(); }
        };
    }

    private static RequestCacheStatsSource requestCacheSource() {
        return new RequestCacheStatsSource() {
            public long getMemorySizeInBytes() { return 0L; }
            public long getEvictions() { return 0L; }
            public long getHitCount() { return 0L; }
            public long getMissCount() { return 0L; }
        };
    }

    private FieldDataStatsSource fieldDataSource() {
        return new FieldDataStatsSource() {
            public long getMemorySizeInBytes() { return breakerService.getBreaker(CircuitBreaker.FIELDDATA).getUsed(); }
            public long getEvictions() { return 0L; }
        };
    }

    private SegmentsStatsSource segmentsSource() {
        return new SegmentsStatsSource() {
            public long getCount() {
                long count = 0;
                for (EngineStats s : engineStats()) {
                    count += s.segmentCount();
                }
                return count;
            }
            public long getMemoryInBytes() { return 0L; }
            public long getTermsMemoryInBytes() { return 0L; }
            public long getStoredFieldsMemoryInBytes() { return 0L; }
            public long getNormsMemoryInBytes() { return 0L; }
            public long getPointsMemoryInBytes() { return 0L; }
            public long getDocValuesMemoryInBytes() { return 0L; }
            public long getIndexWriterMemoryInBytes() { return 0L; }
            public long getVersionMapMemoryInBytes() { return 0L; }
            public long getFixedBitSetMemoryInBytes() { return 0L; }
        };
    }

    private TranslogStatsSource translogSource() {
        return new TranslogStatsSource() {
            public long getOperations() {
                long ops = 0;
                for (EngineStats s : engineStats()) {
                    ops += s.translogNumOps();
                }
                return ops;
            }
            public long getSizeInBytes() {
                long size = 0;
                for (EngineStats s : engineStats()) {
                    size += s.translogSizeInBytes();
                }
                return size;
            }
            public long getUncommittedOperations() { return getOperations(); }
            public long getUncommittedSizeInBytes() { return getSizeInBytes(); }
        };
    }

    private ThreadPoolStatsSource threadPoolSource() {
        return () -> {
            List<ThreadPoolStatsSource.ThreadPoolEntry> entries = new ArrayList<>();
            for (Map.Entry<String, ThreadPool.PoolStats> e : threadPool.stats().pools().entrySet()) {
                ThreadPool.PoolStats p = e.getValue();
                entries.add(new ThreadPoolStatsSource.ThreadPoolEntry(p.name(), p.poolSize(), p.queueSize(), p.active(),
                    p.rejected(), p.completed(), p.poolSize()));
            }
            return entries;
        };
    }

    private static TransportStatsSource transportSource(TransportService transportService) {
        return new TransportStatsSource() {
            public long getRxCount() { return transportService.rxCount(); }
            public long getRxSizeInBytes() { return transportService.rxSizeInBytes(); }
            public long getTxCount() { return transportService.txCount(); }
            public long getTxSizeInBytes() { return transportService.txSizeInBytes(); }
        };
    }

    private HttpStatsSource httpSource() {
        return new HttpStatsSource() {
            public long getCurrentOpen() { return counters.httpCurrentOpen.get(); }
            public long getTotalOpened() { return counters.httpTotalOpened.sum(); }
        };
    }

    private CircuitBreakerStatsSource breakerSource() {
        return () -> {
            List<CircuitBreakerStatsSource.BreakerEntry> entries = new ArrayList<>();
            for (String name : breakerService.allBreakerNames().keySet()) {
                CircuitBreaker b = breakerService.getBreaker(name);
                entries.add(new CircuitBreakerStatsSource.BreakerEntry(name, b.getLimit(), b.getUsed(), b.getOverhead(),
                    b.getTrippedCount()));
            }
            return entries;
        };
    }

    public HealthReport healthReport(BooleanSupplier hasMaster, Supplier<int[]> shardCounts) {
        int[] counts = shardCounts.get();
        List<com.naqqa.elasticsearch.monitor.health.HealthIndicatorSource> indicators = new ArrayList<>();
        indicators.add(new ClusterCoordinationHealthIndicator(new ClusterCoordinationHealthIndicator.Input(hasMaster.getAsBoolean(), false)));
        indicators.add(new ShardsAvailabilityHealthIndicator(new ShardsAvailabilityHealthIndicator.Input(counts[0], counts[1], counts[2], counts[3])));
        Map<String, Double> disk = new java.util.LinkedHashMap<>();
        for (Path p : dataPaths) {
            File f = p.toFile();
            long total = f.getTotalSpace();
            if (total > 0) {
                disk.put(p.toString(), 100.0 * (total - f.getUsableSpace()) / total);
            }
        }
        indicators.add(new DiskHealthIndicator(new DiskHealthIndicator.Input(disk, 90.0, 95.0)));
        return new HealthReport(indicators);
    }
}
