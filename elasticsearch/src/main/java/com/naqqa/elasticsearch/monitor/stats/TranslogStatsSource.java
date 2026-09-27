package com.naqqa.elasticsearch.monitor.stats;

public interface TranslogStatsSource {

    long getOperations();

    long getSizeInBytes();

    long getUncommittedOperations();

    long getUncommittedSizeInBytes();
}
