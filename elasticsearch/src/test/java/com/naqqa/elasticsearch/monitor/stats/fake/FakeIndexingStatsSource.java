package com.naqqa.elasticsearch.monitor.stats.fake;

import com.naqqa.elasticsearch.monitor.stats.IndexingStatsSource;

public final class FakeIndexingStatsSource implements IndexingStatsSource {

    public long indexTotal;
    public long indexTimeInMillis;
    public long indexCurrent;
    public long indexFailed;
    public long deleteTotal;
    public long deleteTimeInMillis;
    public long deleteCurrent;
    public long deleteFailed;

    @Override
    public long getIndexTotal() {
        return indexTotal;
    }

    @Override
    public long getIndexTimeInMillis() {
        return indexTimeInMillis;
    }

    @Override
    public long getIndexCurrent() {
        return indexCurrent;
    }

    @Override
    public long getIndexFailed() {
        return indexFailed;
    }

    @Override
    public long getDeleteTotal() {
        return deleteTotal;
    }

    @Override
    public long getDeleteTimeInMillis() {
        return deleteTimeInMillis;
    }

    @Override
    public long getDeleteCurrent() {
        return deleteCurrent;
    }

    @Override
    public long getDeleteFailed() {
        return deleteFailed;
    }
}
