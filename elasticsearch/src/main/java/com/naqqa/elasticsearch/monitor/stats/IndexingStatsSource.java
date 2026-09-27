package com.naqqa.elasticsearch.monitor.stats;

public interface IndexingStatsSource {

    long getIndexTotal();

    long getIndexTimeInMillis();

    long getIndexCurrent();

    long getIndexFailed();

    long getDeleteTotal();

    long getDeleteTimeInMillis();

    long getDeleteCurrent();

    long getDeleteFailed();
}
