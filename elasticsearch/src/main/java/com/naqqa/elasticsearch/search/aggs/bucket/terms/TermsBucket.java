package com.naqqa.elasticsearch.search.aggs.bucket.terms;

import com.naqqa.elasticsearch.search.aggs.InternalAggregations;
import com.naqqa.elasticsearch.search.aggs.MultiBucketsAggregation;

import java.util.LinkedHashMap;
import java.util.Map;

public final class TermsBucket implements MultiBucketsAggregation.Bucket {

    private final Object key;
    private final long docCount;
    private final long docCountError;
    private final InternalAggregations aggregations;

    public TermsBucket(Object key, long docCount, long docCountError, InternalAggregations aggregations) {
        this.key = key;
        this.docCount = docCount;
        this.docCountError = docCountError;
        this.aggregations = aggregations;
    }

    @Override
    public Object getKey() {
        return key;
    }

    @Override
    public String getKeyAsString() {
        return String.valueOf(key);
    }

    @Override
    public long getDocCount() {
        return docCount;
    }

    public long getDocCountError() {
        return docCountError;
    }

    @Override
    public InternalAggregations getAggregations() {
        return aggregations;
    }

    @Override
    public TermsBucket withAggregations(InternalAggregations aggregations) {
        return new TermsBucket(key, docCount, docCountError, aggregations);
    }

    public Map<String, Object> toMap(boolean showDocCountError) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("key", key);
        map.put("doc_count", docCount);
        if (showDocCountError) {
            map.put("doc_count_error_upper_bound", docCountError);
        }
        map.putAll(aggregations.toMap());
        return map;
    }
}
