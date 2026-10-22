package com.naqqa.search.service;

import com.naqqa.elasticsearch.os.SearchDatasetRegistry;
import com.naqqa.elasticsearch.os.SearchModels.SearchQuery;
import com.naqqa.elasticsearch.os.SearchModels.SearchResult;

public class SearchService {

    private final SearchDatasetRegistry registry;

    public SearchService(SearchDatasetRegistry registry) {
        this.registry = registry;
    }

    public SearchResult search(SearchQuery query) {
        if (query == null) {
            return SearchResult.empty();
        }
        return registry.search(query);
    }
}
