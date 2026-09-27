package com.naqqa.elasticsearch.action.byquery;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record BulkByScrollResponse(long tookMillis, boolean timedOut, boolean cancelled, long total, long updated,
                                    long created, long deleted, int batches, long versionConflicts, long noops,
                                    long retries, long throttledMillis, double requestsPerSecond,
                                    long throttledUntilMillis, List<Map<String, Object>> failures) {

    public BulkByScrollResponse withRetries(long newRetries) {
        return new BulkByScrollResponse(tookMillis, timedOut, cancelled, total, updated, created, deleted, batches,
            versionConflicts, noops, newRetries, throttledMillis, requestsPerSecond, throttledUntilMillis, failures);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("took", tookMillis);
        map.put("timed_out", timedOut);
        map.put("total", total);
        map.put("updated", updated);
        map.put("created", created);
        map.put("deleted", deleted);
        map.put("batches", batches);
        map.put("version_conflicts", versionConflicts);
        map.put("noops", noops);
        Map<String, Object> retriesMap = new LinkedHashMap<>();
        retriesMap.put("bulk", retries);
        retriesMap.put("search", 0);
        map.put("retries", retriesMap);
        map.put("throttled_millis", throttledMillis);
        map.put("requests_per_second", requestsPerSecond);
        map.put("throttled_until_millis", throttledUntilMillis);
        map.put("failures", failures == null ? new ArrayList<>() : failures);
        return map;
    }
}
