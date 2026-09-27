package com.naqqa.elasticsearch.monitor.stats.fake;

import com.naqqa.elasticsearch.monitor.stats.GetStatsSource;

public final class FakeGetStatsSource implements GetStatsSource {

    public long total;
    public long timeInMillis;
    public long existsTotal;
    public long existsTimeInMillis;
    public long missingTotal;
    public long missingTimeInMillis;
    public long current;

    @Override
    public long getTotal() {
        return total;
    }

    @Override
    public long getTimeInMillis() {
        return timeInMillis;
    }

    @Override
    public long getExistsTotal() {
        return existsTotal;
    }

    @Override
    public long getExistsTimeInMillis() {
        return existsTimeInMillis;
    }

    @Override
    public long getMissingTotal() {
        return missingTotal;
    }

    @Override
    public long getMissingTimeInMillis() {
        return missingTimeInMillis;
    }

    @Override
    public long getCurrent() {
        return current;
    }
}
