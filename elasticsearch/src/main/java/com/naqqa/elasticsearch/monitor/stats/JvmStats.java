package com.naqqa.elasticsearch.monitor.stats;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record JvmStats(long uptimeInMillis, Mem mem, Threads threads, List<Gc> gcCollectors) {

    public static JvmStats capture(long uptimeInMillis) {
        MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        MemoryUsage heap = memoryMXBean.getHeapMemoryUsage();
        MemoryUsage nonHeap = memoryMXBean.getNonHeapMemoryUsage();
        Mem mem = new Mem(heap.getUsed(), heap.getCommitted(), heap.getMax(), nonHeap.getUsed(),
                nonHeap.getCommitted(), nonHeap.getMax());
        Threads threads = new Threads(threadMXBean.getThreadCount(), threadMXBean.getPeakThreadCount());
        List<Gc> gcCollectors = new ArrayList<>();
        for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCollectors.add(new Gc(gcBean.getName(), Math.max(0, gcBean.getCollectionCount()),
                    Math.max(0, gcBean.getCollectionTime())));
        }
        return new JvmStats(uptimeInMillis, mem, threads, gcCollectors);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("uptime_in_millis", uptimeInMillis);
        map.put("mem", mem.toMap());
        map.put("threads", threads.toMap());
        Map<String, Object> gcMap = new LinkedHashMap<>();
        Map<String, Object> collectors = new LinkedHashMap<>();
        for (Gc gc : gcCollectors) {
            collectors.put(gc.name(), gc.toMap());
        }
        gcMap.put("collectors", collectors);
        map.put("gc", gcMap);
        return map;
    }

    public record Mem(long heapUsedInBytes, long heapCommittedInBytes, long heapMaxInBytes, long nonHeapUsedInBytes,
            long nonHeapCommittedInBytes, long nonHeapMaxInBytes) {

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("heap_used_in_bytes", heapUsedInBytes);
            map.put("heap_committed_in_bytes", heapCommittedInBytes);
            map.put("heap_max_in_bytes", heapMaxInBytes);
            map.put("non_heap_used_in_bytes", nonHeapUsedInBytes);
            map.put("non_heap_committed_in_bytes", nonHeapCommittedInBytes);
            map.put("non_heap_max_in_bytes", nonHeapMaxInBytes);
            return map;
        }
    }

    public record Threads(int count, int peakCount) {

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("count", count);
            map.put("peak_count", peakCount);
            return map;
        }
    }

    public record Gc(String name, long collectionCount, long collectionTimeInMillis) {

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("collection_count", collectionCount);
            map.put("collection_time_in_millis", collectionTimeInMillis);
            return map;
        }
    }
}
