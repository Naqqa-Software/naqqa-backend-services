package com.naqqa.elasticsearch.monitor.stats;

public interface GetStatsSource {

    long getTotal();

    long getTimeInMillis();

    long getExistsTotal();

    long getExistsTimeInMillis();

    long getMissingTotal();

    long getMissingTimeInMillis();

    long getCurrent();
}
