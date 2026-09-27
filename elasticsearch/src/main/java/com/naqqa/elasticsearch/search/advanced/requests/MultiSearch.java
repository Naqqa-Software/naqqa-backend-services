package com.naqqa.elasticsearch.search.advanced.requests;

import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.Sort;
import com.naqqa.elasticsearch.search.execution.TopDocs;
import com.naqqa.elasticsearch.search.query.Query;

import java.util.ArrayList;
import java.util.List;

public final class MultiSearch {

    private MultiSearch() {
    }

    public record SearchRequest(IndexSearcher searcher, Query query, Sort sort, int size) {

        public SearchRequest(IndexSearcher searcher, Query query, int size) {
            this(searcher, query, null, size);
        }
    }

    public record SearchResult(TopDocs topDocs, Throwable error) {

        public static SearchResult ok(TopDocs topDocs) {
            return new SearchResult(topDocs, null);
        }

        public static SearchResult failed(Throwable error) {
            return new SearchResult(null, error);
        }
    }

    public static List<SearchResult> execute(List<SearchRequest> requests) {
        List<SearchResult> results = new ArrayList<>(requests.size());
        for (SearchRequest r : requests) {
            try {
                TopDocs topDocs = r.sort() != null
                    ? r.searcher().search(r.query(), r.size(), r.sort())
                    : r.searcher().search(r.query(), r.size());
                results.add(SearchResult.ok(topDocs));
            } catch (Exception e) {
                results.add(SearchResult.failed(e));
            }
        }
        return results;
    }
}
