package com.naqqa.elasticsearch.monitor.stats.fake;

import com.naqqa.elasticsearch.monitor.stats.SearchStatsSource;

public final class FakeSearchStatsSource implements SearchStatsSource {

    public long queryTotal;
    public long queryTimeInMillis;
    public long queryCurrent;
    public long fetchTotal;
    public long fetchTimeInMillis;
    public long fetchCurrent;
    public long scrollTotal;
    public long scrollTimeInMillis;
    public long scrollCurrent;
    public long suggestTotal;
    public long suggestTimeInMillis;
    public long suggestCurrent;
    public long openContexts;

    @Override
    public long getQueryTotal() {
        return queryTotal;
    }

    @Override
    public long getQueryTimeInMillis() {
        return queryTimeInMillis;
    }

    @Override
    public long getQueryCurrent() {
        return queryCurrent;
    }

    @Override
    public long getFetchTotal() {
        return fetchTotal;
    }

    @Override
    public long getFetchTimeInMillis() {
        return fetchTimeInMillis;
    }

    @Override
    public long getFetchCurrent() {
        return fetchCurrent;
    }

    @Override
    public long getScrollTotal() {
        return scrollTotal;
    }

    @Override
    public long getScrollTimeInMillis() {
        return scrollTimeInMillis;
    }

    @Override
    public long getScrollCurrent() {
        return scrollCurrent;
    }

    @Override
    public long getSuggestTotal() {
        return suggestTotal;
    }

    @Override
    public long getSuggestTimeInMillis() {
        return suggestTimeInMillis;
    }

    @Override
    public long getSuggestCurrent() {
        return suggestCurrent;
    }

    @Override
    public long getOpenContexts() {
        return openContexts;
    }
}
