package com.naqqa.elasticsearch.search.aggs.bucket.filter;

import com.naqqa.elasticsearch.search.aggs.InternalAggregations;
import com.naqqa.elasticsearch.search.aggs.MultiBucketsAggregation;

import java.util.LinkedHashMap;
import java.util.Map;

public final class FiltersBucket implements MultiBucketsAggregation.Bucket {

    private final String key;
    private final long docCount;
    private final InternalAggregations aggregations;

    public FiltersBucket(String key, long docCount, InternalAggregations aggregations) {
        this.key = key;
        this.docCount = docCount;
        this.aggregations = aggregations;
    }

    @Override
    public Object getKey() {
        return key;
    }

    @Override
    public String getKeyAsString() {
        return key;
    }

    @Override
    public long getDocCount() {
        return docCount;
    }

    @Override
    public InternalAggregations getAggregations() {
        return aggregations;
    }

    @Override
    public FiltersBucket withAggregations(InternalAggregations aggregations) {
        return new FiltersBucket(key, docCount, aggregations);
    }

    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("doc_count", docCount);
        map.putAll(aggregations.toMap());
        return map;
    }
}
