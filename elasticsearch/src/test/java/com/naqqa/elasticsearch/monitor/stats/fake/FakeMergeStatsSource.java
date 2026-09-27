package com.naqqa.elasticsearch.monitor.stats.fake;

import com.naqqa.elasticsearch.monitor.stats.MergeStatsSource;

public final class FakeMergeStatsSource implements MergeStatsSource {

    public long total;
    public long totalTimeInMillis;
    public long current;
    public long totalDocs;
    public long totalSizeInBytes;

    @Override
    public long getTotal() {
        return total;
    }

    @Override
    public long getTotalTimeInMillis() {
        return totalTimeInMillis;
    }

    @Override
    public long getCurrent() {
        return current;
    }

    @Override
    public long getTotalDocs() {
        return totalDocs;
    }

    @Override
    public long getTotalSizeInBytes() {
        return totalSizeInBytes;
    }
}
