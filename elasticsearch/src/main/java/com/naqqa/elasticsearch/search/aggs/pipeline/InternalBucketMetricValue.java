package com.naqqa.elasticsearch.search.aggs.pipeline;

import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.ReduceContext;
import com.naqqa.elasticsearch.search.aggs.SingleValueMetric;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InternalBucketMetricValue extends InternalAggregation implements SingleValueMetric {

    private final double value;
    private final List<String> keys;

    public InternalBucketMetricValue(String name, double value, List<String> keys, Map<String, Object> metadata) {
        super(name, metadata);
        this.value = value;
        this.keys = keys;
    }

    @Override
    public double value() {
        return value;
    }

    public List<String> keys() {
        return keys;
    }

    @Override
    public String getType() {
        return "bucket_metric_value";
    }

    @Override
    public InternalAggregation reduce(List<InternalAggregation> aggregations, ReduceContext context) {
        return aggregations.get(aggregations.size() - 1);
    }

    @Override
    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("value", Double.isNaN(value) ? null : value);
        map.put("keys", keys);
        return map;
    }
}
