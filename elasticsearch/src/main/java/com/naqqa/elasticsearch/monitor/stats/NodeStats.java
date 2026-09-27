package com.naqqa.elasticsearch.monitor.stats;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record NodeStats(String nodeId, String nodeName, long timestamp, CommonStats indices, JvmStats jvm,
        OsStats os, ProcessStats process, FsStats fs, List<ThreadPoolStatsSource.ThreadPoolEntry> threadPools,
        Transport transport, Http http, List<CircuitBreakerStatsSource.BreakerEntry> breakers) {

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("node_id", nodeId);
        map.put("name", nodeName);
        map.put("timestamp", timestamp);
        map.put("indices", indices.toMap());
        map.put("jvm", jvm.toMap());
        map.put("os", os.toMap());
        map.put("process", process.toMap());
        map.put("fs", fs.toMap());
        Map<String, Object> threadPoolMap = new LinkedHashMap<>();
        for (ThreadPoolStatsSource.ThreadPoolEntry entry : threadPools) {
            Map<String, Object> entryMap = new LinkedHashMap<>();
            entryMap.put("threads", entry.threads());
            entryMap.put("queue", entry.queue());
            entryMap.put("active", entry.active());
            entryMap.put("rejected", entry.rejected());
            entryMap.put("completed", entry.completed());
            entryMap.put("largest", entry.largest());
            threadPoolMap.put(entry.name(), entryMap);
        }
        map.put("thread_pool", threadPoolMap);
        map.put("transport", transport.toMap());
        map.put("http", http.toMap());
        Map<String, Object> breakersMap = new LinkedHashMap<>();
        for (CircuitBreakerStatsSource.BreakerEntry entry : breakers) {
            Map<String, Object> entryMap = new LinkedHashMap<>();
            entryMap.put("limit_size_in_bytes", entry.limitInBytes());
            entryMap.put("estimated_size_in_bytes", entry.estimatedInBytes());
            entryMap.put("overhead", entry.overhead());
            entryMap.put("tripped", entry.tripped());
            breakersMap.put(entry.name(), entryMap);
        }
        map.put("breakers", breakersMap);
        return map;
    }

    public record Transport(long rxCount, long rxSizeInBytes, long txCount, long txSizeInBytes) {

        public static Transport from(TransportStatsSource source) {
            return new Transport(source.getRxCount(), source.getRxSizeInBytes(), source.getTxCount(),
                    source.getTxSizeInBytes());
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("rx_count", rxCount);
            map.put("rx_size_in_bytes", rxSizeInBytes);
            map.put("tx_count", txCount);
            map.put("tx_size_in_bytes", txSizeInBytes);
            return map;
        }
    }

    public record Http(long currentOpen, long totalOpened) {

        public static Http from(HttpStatsSource source) {
            return new Http(source.getCurrentOpen(), source.getTotalOpened());
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("current_open", currentOpen);
            map.put("total_opened", totalOpened);
            return map;
        }
    }

    public static List<CircuitBreakerStatsSource.BreakerEntry> copyBreakers(CircuitBreakerStatsSource source) {
        return new ArrayList<>(source.getBreakers());
    }
}
