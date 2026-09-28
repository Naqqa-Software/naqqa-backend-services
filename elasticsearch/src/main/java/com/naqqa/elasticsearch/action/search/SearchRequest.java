package com.naqqa.elasticsearch.action.search;

import com.naqqa.elasticsearch.search.aggs.support.MultiBucketConsumer;
import com.naqqa.elasticsearch.search.execution.Sort;
import com.naqqa.elasticsearch.search.execution.TotalHits;
import com.naqqa.elasticsearch.search.query.Query;

import java.util.Map;

public final class SearchRequest {

    public record CanMatchRange(String field, long lower, long upper) {
    }

    private final String index;
    private final Query query;
    private Sort sort;
    private int from = 0;
    private int size = 10;
    private String preference;
    private boolean allowPartialSearchResults = true;
    private SearchType searchType = SearchType.QUERY_THEN_FETCH;
    private int preFilterShardSize = 128;
    private long timeoutMillis = 30_000L;
    private CanMatchRange canMatchRange;
    private boolean useAdaptiveReplicaSelection = false;
    private Map<String, Object> aggs;
    private int maxBuckets = MultiBucketConsumer.DEFAULT_MAX_BUCKETS;
    private long trackTotalHitsUpTo = TotalHits.TRACK_TOTAL_HITS_ACCURATE;
    private boolean totalHitsAsInt = false;

    public SearchRequest(String index, Query query) {
        this.index = index;
        this.query = query;
    }

    public String index() {
        return index;
    }

    public Query query() {
        return query;
    }

    public Sort sort() {
        return sort;
    }

    public SearchRequest sort(Sort sort) {
        this.sort = sort;
        return this;
    }

    public int from() {
        return from;
    }

    public SearchRequest from(int from) {
        this.from = from;
        return this;
    }

    public int size() {
        return size;
    }

    public SearchRequest size(int size) {
        this.size = size;
        return this;
    }

    public String preference() {
        return preference;
    }

    public SearchRequest preference(String preference) {
        this.preference = preference;
        return this;
    }

    public boolean allowPartialSearchResults() {
        return allowPartialSearchResults;
    }

    public SearchRequest allowPartialSearchResults(boolean value) {
        this.allowPartialSearchResults = value;
        return this;
    }

    public SearchType searchType() {
        return searchType;
    }

    public SearchRequest searchType(SearchType searchType) {
        this.searchType = searchType;
        return this;
    }

    public int preFilterShardSize() {
        return preFilterShardSize;
    }

    public SearchRequest preFilterShardSize(int preFilterShardSize) {
        this.preFilterShardSize = preFilterShardSize;
        return this;
    }

    public long timeoutMillis() {
        return timeoutMillis;
    }

    public SearchRequest timeoutMillis(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
        return this;
    }

    public CanMatchRange canMatchRange() {
        return canMatchRange;
    }

    public SearchRequest canMatchRange(CanMatchRange canMatchRange) {
        this.canMatchRange = canMatchRange;
        return this;
    }

    public boolean useAdaptiveReplicaSelection() {
        return useAdaptiveReplicaSelection;
    }

    public SearchRequest useAdaptiveReplicaSelection(boolean value) {
        this.useAdaptiveReplicaSelection = value;
        return this;
    }

    public Map<String, Object> aggs() {
        return aggs;
    }

    public SearchRequest aggs(Map<String, Object> aggs) {
        this.aggs = aggs;
        return this;
    }

    public int maxBuckets() {
        return maxBuckets;
    }

    public SearchRequest maxBuckets(int maxBuckets) {
        this.maxBuckets = maxBuckets;
        return this;
    }

    public long trackTotalHitsUpTo() {
        return trackTotalHitsUpTo;
    }

    public SearchRequest trackTotalHitsUpTo(long trackTotalHitsUpTo) {
        this.trackTotalHitsUpTo = trackTotalHitsUpTo;
        return this;
    }

    public boolean totalHitsAsInt() {
        return totalHitsAsInt;
    }

    public SearchRequest totalHitsAsInt(boolean totalHitsAsInt) {
        this.totalHitsAsInt = totalHitsAsInt;
        return this;
    }
}
