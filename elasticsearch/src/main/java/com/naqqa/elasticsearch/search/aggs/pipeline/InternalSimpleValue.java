package com.naqqa.elasticsearch.search.aggs.pipeline;

import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.ReduceContext;
import com.naqqa.elasticsearch.search.aggs.SingleValueMetric;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InternalSimpleValue extends InternalAggregation implements SingleValueMetric {

    private final double value;

    public InternalSimpleValue(String name, double value, Map<String, Object> metadata) {
        super(name, metadata);
        this.value = value;
    }

    @Override
    public double value() {
        return value;
    }

    @Override
    public String getType() {
        return "simple_value";
    }

    @Override
    public InternalAggregation reduce(List<InternalAggregation> aggregations, ReduceContext context) {
        return aggregations.get(aggregations.size() - 1);
    }

    @Override
    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("value", Double.isNaN(value) ? null : value);
        return map;
    }
}
