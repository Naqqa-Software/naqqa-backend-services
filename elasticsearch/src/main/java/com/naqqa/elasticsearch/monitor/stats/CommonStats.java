package com.naqqa.elasticsearch.monitor.stats;

import java.util.LinkedHashMap;
import java.util.Map;

public record CommonStats(Indexing indexing, Search search, Get get, Merges merges, Refresh refresh, Flush flush,
        QueryCache queryCache, RequestCache requestCache, FieldData fieldData, Segments segments, Translog translog) {

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("indexing", indexing.toMap());
        map.put("search", search.toMap());
        map.put("get", get.toMap());
        map.put("merges", merges.toMap());
        map.put("refresh", refresh.toMap());
        map.put("flush", flush.toMap());
        map.put("query_cache", queryCache.toMap());
        map.put("request_cache", requestCache.toMap());
        map.put("fielddata", fieldData.toMap());
        map.put("segments", segments.toMap());
        map.put("translog", translog.toMap());
        return map;
    }

    public static CommonStats fromSources(IndexingStatsSource indexingSource, SearchStatsSource searchSource,
            GetStatsSource getSource, MergeStatsSource mergeSource, RefreshFlushStatsSource refreshFlushSource,
            QueryCacheStatsSource queryCacheSource, RequestCacheStatsSource requestCacheSource,
            FieldDataStatsSource fieldDataSource, SegmentsStatsSource segmentsSource, TranslogStatsSource translogSource) {
        return new CommonStats(
                Indexing.from(indexingSource),
                Search.from(searchSource),
                Get.from(getSource),
                Merges.from(mergeSource),
                Refresh.from(refreshFlushSource),
                Flush.from(refreshFlushSource),
                QueryCache.from(queryCacheSource),
                RequestCache.from(requestCacheSource),
                FieldData.from(fieldDataSource),
                Segments.from(segmentsSource),
                Translog.from(translogSource));
    }

    public record Indexing(long indexTotal, long indexTimeInMillis, long indexCurrent, long indexFailed,
            long deleteTotal, long deleteTimeInMillis, long deleteCurrent, long deleteFailed) {

        public static Indexing from(IndexingStatsSource source) {
            return new Indexing(source.getIndexTotal(), source.getIndexTimeInMillis(), source.getIndexCurrent(),
                    source.getIndexFailed(), source.getDeleteTotal(), source.getDeleteTimeInMillis(),
                    source.getDeleteCurrent(), source.getDeleteFailed());
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("index_total", indexTotal);
            map.put("index_time_in_millis", indexTimeInMillis);
            map.put("index_current", indexCurrent);
            map.put("index_failed", indexFailed);
            map.put("delete_total", deleteTotal);
            map.put("delete_time_in_millis", deleteTimeInMillis);
            map.put("delete_current", deleteCurrent);
            map.put("delete_failed", deleteFailed);
            return map;
        }
    }

    public record Search(long queryTotal, long queryTimeInMillis, long queryCurrent, long fetchTotal,
            long fetchTimeInMillis, long fetchCurrent, long scrollTotal, long scrollTimeInMillis, long scrollCurrent,
            long suggestTotal, long suggestTimeInMillis, long suggestCurrent, long openContexts) {

        public static Search from(SearchStatsSource source) {
            return new Search(source.getQueryTotal(), source.getQueryTimeInMillis(), source.getQueryCurrent(),
                    source.getFetchTotal(), source.getFetchTimeInMillis(), source.getFetchCurrent(),
                    source.getScrollTotal(), source.getScrollTimeInMillis(), source.getScrollCurrent(),
                    source.getSuggestTotal(), source.getSuggestTimeInMillis(), source.getSuggestCurrent(),
                    source.getOpenContexts());
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("query_total", queryTotal);
            map.put("query_time_in_millis", queryTimeInMillis);
            map.put("query_current", queryCurrent);
            map.put("fetch_total", fetchTotal);
            map.put("fetch_time_in_millis", fetchTimeInMillis);
            map.put("fetch_current", fetchCurrent);
            map.put("scroll_total", scrollTotal);
            map.put("scroll_time_in_millis", scrollTimeInMillis);
            map.put("scroll_current", scrollCurrent);
            map.put("suggest_total", suggestTotal);
            map.put("suggest_time_in_millis", suggestTimeInMillis);
            map.put("suggest_current", suggestCurrent);
            map.put("open_contexts", openContexts);
            return map;
        }
    }

    public record Get(long total, long timeInMillis, long existsTotal, long existsTimeInMillis, long missingTotal,
            long missingTimeInMillis, long current) {

        public static Get from(GetStatsSource source) {
            return new Get(source.getTotal(), source.getTimeInMillis(), source.getExistsTotal(),
                    source.getExistsTimeInMillis(), source.getMissingTotal(), source.getMissingTimeInMillis(),
                    source.getCurrent());
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("total", total);
            map.put("time_in_millis", timeInMillis);
            map.put("exists_total", existsTotal);
            map.put("exists_time_in_millis", existsTimeInMillis);
            map.put("missing_total", missingTotal);
            map.put("missing_time_in_millis", missingTimeInMillis);
            map.put("current", current);
            return map;
        }
    }

    public record Merges(long total, long totalTimeInMillis, long current, long totalDocs, long totalSizeInBytes) {

        public static Merges from(MergeStatsSource source) {
            return new Merges(source.getTotal(), source.getTotalTimeInMillis(), source.getCurrent(),
                    source.getTotalDocs(), source.getTotalSizeInBytes());
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("total", total);
            map.put("total_time_in_millis", totalTimeInMillis);
            map.put("current", current);
            map.put("total_docs", totalDocs);
            map.put("total_size_in_bytes", totalSizeInBytes);
            return map;
        }
    }

    public record Refresh(long total, long totalTimeInMillis) {

        public static Refresh from(RefreshFlushStatsSource source) {
            return new Refresh(source.getRefreshTotal(), source.getRefreshTotalTimeInMillis());
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("total", total);
            map.put("total_time_in_millis", totalTimeInMillis);
            return map;
        }
    }

    public record Flush(long total, long totalTimeInMillis) {

        public static Flush from(RefreshFlushStatsSource source) {
            return new Flush(source.getFlushTotal(), source.getFlushTotalTimeInMillis());
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("total", total);
            map.put("total_time_in_millis", totalTimeInMillis);
            return map;
        }
    }

    public record QueryCache(long cacheSize, long cacheCount, long evictions, long hitCount, long missCount,
            long memorySizeInBytes) {

        public static QueryCache from(QueryCacheStatsSource source) {
            return new QueryCache(source.getCacheSize(), source.getCacheCount(), source.getEvictions(),
                    source.getHitCount(), source.getMissCount(), source.getMemorySizeInBytes());
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("cache_size", cacheSize);
            map.put("cache_count", cacheCount);
            map.put("evictions", evictions);
            map.put("hit_count", hitCount);
            map.put("miss_count", missCount);
            map.put("memory_size_in_bytes", memorySizeInBytes);
            return map;
        }
    }

    public record RequestCache(long memorySizeInBytes, long evictions, long hitCount, long missCount) {

        public static RequestCache from(RequestCacheStatsSource source) {
            return new RequestCache(source.getMemorySizeInBytes(), source.getEvictions(), source.getHitCount(),
                    source.getMissCount());
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("memory_size_in_bytes", memorySizeInBytes);
            map.put("evictions", evictions);
            map.put("hit_count", hitCount);
            map.put("miss_count", missCount);
            return map;
        }
    }

    public record FieldData(long memorySizeInBytes, long evictions) {

        public static FieldData from(FieldDataStatsSource source) {
            return new FieldData(source.getMemorySizeInBytes(), source.getEvictions());
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("memory_size_in_bytes", memorySizeInBytes);
            map.put("evictions", evictions);
            return map;
        }
    }

    public record Segments(long count, long memoryInBytes, long termsMemoryInBytes, long storedFieldsMemoryInBytes,
            long normsMemoryInBytes, long pointsMemoryInBytes, long docValuesMemoryInBytes,
            long indexWriterMemoryInBytes, long versionMapMemoryInBytes, long fixedBitSetMemoryInBytes) {

        public static Segments from(SegmentsStatsSource source) {
            return new Segments(source.getCount(), source.getMemoryInBytes(), source.getTermsMemoryInBytes(),
                    source.getStoredFieldsMemoryInBytes(), source.getNormsMemoryInBytes(),
                    source.getPointsMemoryInBytes(), source.getDocValuesMemoryInBytes(),
                    source.getIndexWriterMemoryInBytes(), source.getVersionMapMemoryInBytes(),
                    source.getFixedBitSetMemoryInBytes());
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("count", count);
            map.put("memory_in_bytes", memoryInBytes);
            map.put("terms_memory_in_bytes", termsMemoryInBytes);
            map.put("stored_fields_memory_in_bytes", storedFieldsMemoryInBytes);
            map.put("norms_memory_in_bytes", normsMemoryInBytes);
            map.put("points_memory_in_bytes", pointsMemoryInBytes);
            map.put("doc_values_memory_in_bytes", docValuesMemoryInBytes);
            map.put("index_writer_memory_in_bytes", indexWriterMemoryInBytes);
            map.put("version_map_memory_in_bytes", versionMapMemoryInBytes);
            map.put("fixed_bit_set_memory_in_bytes", fixedBitSetMemoryInBytes);
            return map;
        }
    }

    public record Translog(long operations, long sizeInBytes, long uncommittedOperations,
            long uncommittedSizeInBytes) {

        public static Translog from(TranslogStatsSource source) {
            return new Translog(source.getOperations(), source.getSizeInBytes(), source.getUncommittedOperations(),
                    source.getUncommittedSizeInBytes());
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("operations", operations);
            map.put("size_in_bytes", sizeInBytes);
            map.put("uncommitted_operations", uncommittedOperations);
            map.put("uncommitted_size_in_bytes", uncommittedSizeInBytes);
            return map;
        }
    }
}
