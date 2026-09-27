package com.naqqa.elasticsearch.monitor.stats;

public interface QueryCacheStatsSource {

    long getCacheSize();

    long getCacheCount();

    long getEvictions();

    long getHitCount();

    long getMissCount();

    long getMemorySizeInBytes();
}
