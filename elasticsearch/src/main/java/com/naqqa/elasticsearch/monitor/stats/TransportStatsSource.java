package com.naqqa.elasticsearch.monitor.stats;

public interface TransportStatsSource {

    long getRxCount();

    long getRxSizeInBytes();

    long getTxCount();

    long getTxSizeInBytes();
}
