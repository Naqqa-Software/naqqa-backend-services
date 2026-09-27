package com.naqqa.elasticsearch.monitor.stats.fake;

import com.naqqa.elasticsearch.monitor.stats.FieldDataStatsSource;

public final class FakeFieldDataStatsSource implements FieldDataStatsSource {

    public long memorySizeInBytes;
    public long evictions;

    @Override
    public long getMemorySizeInBytes() {
        return memorySizeInBytes;
    }

    @Override
    public long getEvictions() {
        return evictions;
    }
}
