package com.naqqa.elasticsearch.search.execution;

public final class QueryCaches {

    private static volatile QueryCache shared = new QueryCache();

    private QueryCaches() {
    }

    public static QueryCache shared() {
        return shared;
    }

    public static void setShared(QueryCache cache) {
        shared = cache != null ? cache : new QueryCache();
    }
}
