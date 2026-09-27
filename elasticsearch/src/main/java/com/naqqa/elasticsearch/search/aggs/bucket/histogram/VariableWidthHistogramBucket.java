package com.naqqa.elasticsearch.search.aggs.bucket.histogram;

import com.naqqa.elasticsearch.search.aggs.InternalAggregations;
import com.naqqa.elasticsearch.search.aggs.MultiBucketsAggregation;

import java.util.LinkedHashMap;
import java.util.Map;

public final class VariableWidthHistogramBucket implements MultiBucketsAggregation.Bucket {

    private final double key;
    private final double min;
    private final double max;
    private final long docCount;
    private final InternalAggregations aggregations;

    public VariableWidthHistogramBucket(double key, double min, double max, long docCount, InternalAggregations aggregations) {
        this.key = key;
        this.min = min;
        this.max = max;
        this.docCount = docCount;
        this.aggregations = aggregations;
    }

    public double getMin() {
        return min;
    }

    public double getMax() {
        return max;
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

    @Override
    public InternalAggregations getAggregations() {
        return aggregations;
    }

    @Override
    public VariableWidthHistogramBucket withAggregations(InternalAggregations aggregations) {
        return new VariableWidthHistogramBucket(key, min, max, docCount, aggregations);
    }

    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("key", key);
        map.put("min", min);
        map.put("max", max);
        map.put("doc_count", docCount);
        map.putAll(aggregations.toMap());
        return map;
    }
}
