package com.naqqa.elasticsearch.monitor.stats;

import java.util.List;

public interface ThreadPoolStatsSource {

    List<ThreadPoolEntry> getThreadPools();

    record ThreadPoolEntry(String name, int threads, int queue, int active, long rejected, long completed, int largest) {
    }
}
