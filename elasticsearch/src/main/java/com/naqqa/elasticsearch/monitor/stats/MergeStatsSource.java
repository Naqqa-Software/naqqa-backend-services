package com.naqqa.elasticsearch.monitor.stats;

public interface MergeStatsSource {

    long getTotal();

    long getTotalTimeInMillis();

    long getCurrent();

    long getTotalDocs();

    long getTotalSizeInBytes();
}
