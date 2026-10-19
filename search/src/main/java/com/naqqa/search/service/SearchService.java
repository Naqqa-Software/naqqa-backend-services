package com.naqqa.search.service;

import com.naqqa.elasticsearch.os.SearchDatasetRegistry;
import com.naqqa.elasticsearch.os.SearchModels.SearchQuery;
import com.naqqa.elasticsearch.os.SearchModels.SearchResult;

/**
 * Application service for the search module. It owns the query-handling logic the controller
 * previously held inline (the null-query fallback) and delegates matching to the
 * {@link SearchDatasetRegistry} outbound adapter from naqqa-elasticsearch. Introducing this layer
 * brings the module to the hexagonal web/service shape in ARCHITECTURE.md, so the web adapter only
 * maps HTTP while the registry stays the out-of-process query engine. The request/response contract
 * (the {@code SearchModels} records from naqqa-elasticsearch) is unchanged.
 */
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
