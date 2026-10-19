package com.naqqa.elasticsearch.os;

import java.util.List;
import java.util.Map;

/**
 * Request/response model for the unified {@code POST /api/search} contract consumed by naqqa-os.
 * Mirrors {@code naqqa-os-fe/src/app/core/search/search.models.ts}. Filters and aggregations are
 * modelled as flat records with a {@code type} discriminator so they deserialize from the OS JSON
 * (a discriminated union) without custom Jackson handling.
 */
public final class SearchModels {

    private SearchModels() {
    }

    public record SearchFilter(String type, String field, Object value, List<Object> values,
                               Double gte, Double lte, Double gt, Double lt) {
    }

    public record SortClause(String field, String dir) {
    }

    public record RangeAggBucket(String key, Double from, Double to) {
    }

    public record AggRequest(String name, String type, String field, Integer size,
                             List<RangeAggBucket> ranges, Long intervalMs, String fieldUnit) {
    }

    public record SearchQuery(String index, String text, List<SearchFilter> filters, List<SortClause> sort,
                              Integer from, Integer size, List<Object> searchAfter, List<AggRequest> aggs) {
    }

    public record FacetBucket(String key, long count, Double from, Double to) {
    }

    public record SearchHit(String id, double score, Object source, List<Object> sort) {
    }

    public record SearchResult(List<SearchHit> hits, long total, Map<String, List<FacetBucket>> aggregations,
                               long took) {
        public static SearchResult empty() {
            return new SearchResult(List.of(), 0, Map.of(), 0);
        }
    }
}
