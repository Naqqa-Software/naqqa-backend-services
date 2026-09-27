package com.naqqa.elasticsearch.monitor.stats;

public interface SearchStatsSource {

    long getQueryTotal();

    long getQueryTimeInMillis();

    long getQueryCurrent();

    long getFetchTotal();

    long getFetchTimeInMillis();

    long getFetchCurrent();

    long getScrollTotal();

    long getScrollTimeInMillis();

    long getScrollCurrent();

    long getSuggestTotal();

    long getSuggestTimeInMillis();

    long getSuggestCurrent();

    long getOpenContexts();
}
