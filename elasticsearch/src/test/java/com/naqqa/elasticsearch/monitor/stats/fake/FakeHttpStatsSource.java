package com.naqqa.elasticsearch.monitor.stats.fake;

import com.naqqa.elasticsearch.monitor.stats.HttpStatsSource;

public final class FakeHttpStatsSource implements HttpStatsSource {

    public long currentOpen;
    public long totalOpened;

    @Override
    public long getCurrentOpen() {
        return currentOpen;
    }

    @Override
    public long getTotalOpened() {
        return totalOpened;
    }
}
