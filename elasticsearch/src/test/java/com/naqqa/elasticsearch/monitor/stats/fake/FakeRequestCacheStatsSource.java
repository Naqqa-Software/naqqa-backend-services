package com.naqqa.elasticsearch.monitor.stats.fake;

import com.naqqa.elasticsearch.monitor.stats.RequestCacheStatsSource;

public final class FakeRequestCacheStatsSource implements RequestCacheStatsSource {

    public long memorySizeInBytes;
    public long evictions;
    public long hitCount;
    public long missCount;

    @Override
    public long getMemorySizeInBytes() {
        return memorySizeInBytes;
    }

    @Override
    public long getEvictions() {
        return evictions;
    }

    @Override
    public long getHitCount() {
        return hitCount;
    }

    @Override
    public long getMissCount() {
        return missCount;
    }
}
