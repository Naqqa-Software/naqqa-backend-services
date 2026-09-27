package com.naqqa.elasticsearch.search.advanced.pagination;

import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.Sort;
import com.naqqa.elasticsearch.search.execution.TotalHits;
import com.naqqa.elasticsearch.search.query.Query;

import java.io.IOException;

public final class Pagination {

    private Pagination() {
    }

    public record Page(FieldDoc[] hits, TotalHits totalHits) {
    }

    public static Page searchAfter(IndexSearcher searcher, Query query, Sort sort, int size, FieldDoc after) throws IOException {
        SearchAfterCollector collector = SearchAfterCollector.create(sort, size, after);
        searcher.search(query, collector);
        return new Page(collector.pageResults(), collector.totalHits());
    }
}
