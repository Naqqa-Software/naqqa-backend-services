package com.naqqa.elasticsearch.search.aggs.bucket.composite;

import com.naqqa.elasticsearch.search.aggs.InternalAggregations;
import com.naqqa.elasticsearch.search.aggs.MultiBucketsAggregation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CompositeBucket implements MultiBucketsAggregation.Bucket {

    private final List<String> sourceNames;
    private final List<Object> key;
    private final long docCount;
    private final InternalAggregations aggregations;

    public CompositeBucket(List<String> sourceNames, List<Object> key, long docCount, InternalAggregations aggregations) {
        this.sourceNames = sourceNames;
        this.key = key;
        this.docCount = docCount;
        this.aggregations = aggregations;
    }

    public Map<String, Object> keyAsMap() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < sourceNames.size(); i++) {
            map.put(sourceNames.get(i), key.get(i));
        }
        return map;
    }

    @Override
    public Object getKey() {
        return key;
    }

    @Override
    public String getKeyAsString() {
        return String.valueOf(keyAsMap());
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
    public CompositeBucket withAggregations(InternalAggregations aggregations) {
        return new CompositeBucket(sourceNames, key, docCount, aggregations);
    }

    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("key", keyAsMap());
        map.put("doc_count", docCount);
        map.putAll(aggregations.toMap());
        return map;
    }
}
