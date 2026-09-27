package com.naqqa.elasticsearch.monitor.stats.fake;

import com.naqqa.elasticsearch.monitor.stats.ThreadPoolStatsSource;
import java.util.ArrayList;
import java.util.List;

public final class FakeThreadPoolStatsSource implements ThreadPoolStatsSource {

    public final List<ThreadPoolEntry> entries = new ArrayList<>();

    @Override
    public List<ThreadPoolEntry> getThreadPools() {
        return entries;
    }
}
