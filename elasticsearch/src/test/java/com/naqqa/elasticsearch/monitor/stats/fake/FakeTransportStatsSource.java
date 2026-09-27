package com.naqqa.elasticsearch.monitor.stats.fake;

import com.naqqa.elasticsearch.monitor.stats.TransportStatsSource;

public final class FakeTransportStatsSource implements TransportStatsSource {

    public long rxCount;
    public long rxSizeInBytes;
    public long txCount;
    public long txSizeInBytes;

    @Override
    public long getRxCount() {
        return rxCount;
    }

    @Override
    public long getRxSizeInBytes() {
        return rxSizeInBytes;
    }

    @Override
    public long getTxCount() {
        return txCount;
    }

    @Override
    public long getTxSizeInBytes() {
        return txSizeInBytes;
    }
}
