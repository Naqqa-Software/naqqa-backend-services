package com.naqqa.elasticsearch.search.advanced.requests;

import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.query.Query;

import java.io.IOException;
import java.util.List;

public final class SearchCount {

    private SearchCount() {
    }

    public static long count(IndexSearcher searcher, Query query) throws IOException {
        return searcher.count(query);
    }

    public static long count(List<IndexSearcher> searchers, Query query) throws IOException {
        long total = 0;
        for (IndexSearcher s : searchers) {
            total += s.count(query);
        }
        return total;
    }
}
