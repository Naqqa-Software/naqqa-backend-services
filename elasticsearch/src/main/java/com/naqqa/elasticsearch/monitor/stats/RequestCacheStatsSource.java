package com.naqqa.elasticsearch.monitor.stats;

public interface RequestCacheStatsSource {

    long getMemorySizeInBytes();

    long getEvictions();

    long getHitCount();

    long getMissCount();
}
