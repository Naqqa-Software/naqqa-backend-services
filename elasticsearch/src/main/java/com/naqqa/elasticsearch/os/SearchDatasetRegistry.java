package com.naqqa.elasticsearch.os;

import com.naqqa.elasticsearch.os.SearchModels.SearchQuery;
import com.naqqa.elasticsearch.os.SearchModels.SearchResult;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds the datasets available to {@code POST /api/search} by index name and runs queries against them.
 * Mirrors the {@code SearchEngine} registry in {@code search-engine.ts}.
 */
public final class SearchDatasetRegistry {

    private final Map<String, SearchDataset> datasets = new ConcurrentHashMap<>();

    public void register(SearchDataset dataset) {
        datasets.put(dataset.index(), dataset);
    }

    public boolean has(String index) {
        return datasets.containsKey(index);
    }

    public SearchResult search(SearchQuery query) {
        if (query == null || query.index() == null) {
            return SearchResult.empty();
        }
        SearchDataset dataset = datasets.get(query.index());
        if (dataset == null) {
            return SearchResult.empty();
        }
        return SearchExecutor.runSearch(dataset, query);
    }
}
