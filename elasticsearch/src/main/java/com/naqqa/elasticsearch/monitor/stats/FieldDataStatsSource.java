package com.naqqa.elasticsearch.monitor.stats;

public interface FieldDataStatsSource {

    long getMemorySizeInBytes();

    long getEvictions();
}
