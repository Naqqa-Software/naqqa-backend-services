package com.naqqa.elasticsearch.monitor.stats;

public interface RefreshFlushStatsSource {

    long getRefreshTotal();

    long getRefreshTotalTimeInMillis();

    long getFlushTotal();

    long getFlushTotalTimeInMillis();
}
