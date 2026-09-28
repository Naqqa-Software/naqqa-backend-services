package com.naqqa.elasticsearch.bench;

import java.util.LinkedHashMap;
import java.util.Map;

public final class IndexingResult {

    public final long docsIndexed;
    public final long errorCount;
    public final double docsPerSec;
    public final double p50Ms;
    public final double p90Ms;
    public final double p99Ms;
    public long refreshMs = -1;
    public long forceMergeMs = -1;

    public IndexingResult(long docsIndexed, long errorCount, double docsPerSec, double p50Ms, double p90Ms, double p99Ms) {
        this.docsIndexed = docsIndexed;
        this.errorCount = errorCount;
        this.docsPerSec = docsPerSec;
        this.p50Ms = p50Ms;
        this.p90Ms = p90Ms;
        this.p99Ms = p99Ms;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("docs_indexed", docsIndexed);
        m.put("error_count", errorCount);
        m.put("docs_per_sec", docsPerSec);
        m.put("bulk_p50_ms", p50Ms);
        m.put("bulk_p90_ms", p90Ms);
        m.put("bulk_p99_ms", p99Ms);
        m.put("refresh_ms", refreshMs);
        m.put("force_merge_ms", forceMergeMs);
        return m;
    }
}
