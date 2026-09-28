package com.naqqa.elasticsearch.bench;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TrackResult {

    public final String trackName;
    public final long docCount;
    public final IndexingResult indexing;
    public final List<OperationResult> queries;
    public final long indexDocCount;
    public final long storeSizeBytes;
    public final long segmentCount;
    public final long jvmHeapUsedBytes;

    public TrackResult(String trackName, long docCount, IndexingResult indexing, List<OperationResult> queries,
                        long indexDocCount, long storeSizeBytes, long segmentCount, long jvmHeapUsedBytes) {
        this.trackName = trackName;
        this.docCount = docCount;
        this.indexing = indexing;
        this.queries = queries;
        this.indexDocCount = indexDocCount;
        this.storeSizeBytes = storeSizeBytes;
        this.segmentCount = segmentCount;
        this.jvmHeapUsedBytes = jvmHeapUsedBytes;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("track", trackName);
        m.put("requested_doc_count", docCount);
        m.put("indexing", indexing.toMap());
        List<Map<String, Object>> queryMaps = new java.util.ArrayList<>();
        for (OperationResult r : queries) {
            queryMaps.add(r.toMap());
        }
        m.put("queries", queryMaps);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("doc_count", indexDocCount);
        stats.put("store_size_bytes", storeSizeBytes);
        stats.put("segment_count", segmentCount);
        m.put("index_stats", stats);
        m.put("jvm_heap_used_bytes", jvmHeapUsedBytes);
        return m;
    }
}
