package com.naqqa.elasticsearch.monitor.stats;

public interface HttpStatsSource {

    long getCurrentOpen();

    long getTotalOpened();
}
