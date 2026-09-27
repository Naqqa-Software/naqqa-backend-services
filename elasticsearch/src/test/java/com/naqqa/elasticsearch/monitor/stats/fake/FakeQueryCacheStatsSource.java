package com.naqqa.elasticsearch.monitor.stats.fake;

import com.naqqa.elasticsearch.monitor.stats.QueryCacheStatsSource;

public final class FakeQueryCacheStatsSource implements QueryCacheStatsSource {

    public long cacheSize;
    public long cacheCount;
    public long evictions;
    public long hitCount;
    public long missCount;
    public long memorySizeInBytes;

    @Override
    public long getCacheSize() {
        return cacheSize;
    }

    @Override
    public long getCacheCount() {
        return cacheCount;
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

    @Override
    public long getMemorySizeInBytes() {
        return memorySizeInBytes;
    }
}
