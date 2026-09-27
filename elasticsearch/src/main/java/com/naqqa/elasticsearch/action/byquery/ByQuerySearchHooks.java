package com.naqqa.elasticsearch.action.byquery;

import com.naqqa.elasticsearch.action.search.SearchCoordinator;
import com.naqqa.elasticsearch.action.search.SearchRequest;
import com.naqqa.elasticsearch.action.search.SearchResponse;
import com.naqqa.elasticsearch.cluster.routing.RoutingTable;
import com.naqqa.elasticsearch.search.query.Query;

import java.io.IOException;
import java.util.Map;

public record ByQuerySearchHooks(QueryConverter queryConverter, Searcher searcher) {

    @FunctionalInterface
    public interface QueryConverter {
        Query convert(String index, Map<String, Object> queryClause);
    }

    @FunctionalInterface
    public interface Searcher {
        SearchResponse search(RoutingTable routingTable, SearchRequest request) throws IOException;
    }

    public static ByQuerySearchHooks defaults(SearchCoordinator coordinator) {
        return new ByQuerySearchHooks((index, clause) -> QueryClauseConverter.convert(clause), coordinator::search);
    }
}
