package com.naqqa.elasticsearch.monitor.stats.fake;

import com.naqqa.elasticsearch.monitor.stats.RefreshFlushStatsSource;

public final class FakeRefreshFlushStatsSource implements RefreshFlushStatsSource {

    public long refreshTotal;
    public long refreshTotalTimeInMillis;
    public long flushTotal;
    public long flushTotalTimeInMillis;

    @Override
    public long getRefreshTotal() {
        return refreshTotal;
    }

    @Override
    public long getRefreshTotalTimeInMillis() {
        return refreshTotalTimeInMillis;
    }

    @Override
    public long getFlushTotal() {
        return flushTotal;
    }

    @Override
    public long getFlushTotalTimeInMillis() {
        return flushTotalTimeInMillis;
    }
}
