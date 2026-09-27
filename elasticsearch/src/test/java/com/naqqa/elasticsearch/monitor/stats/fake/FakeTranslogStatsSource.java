package com.naqqa.elasticsearch.monitor.stats.fake;

import com.naqqa.elasticsearch.monitor.stats.TranslogStatsSource;

public final class FakeTranslogStatsSource implements TranslogStatsSource {

    public long operations;
    public long sizeInBytes;
    public long uncommittedOperations;
    public long uncommittedSizeInBytes;

    @Override
    public long getOperations() {
        return operations;
    }

    @Override
    public long getSizeInBytes() {
        return sizeInBytes;
    }

    @Override
    public long getUncommittedOperations() {
        return uncommittedOperations;
    }

    @Override
    public long getUncommittedSizeInBytes() {
        return uncommittedSizeInBytes;
    }
}
