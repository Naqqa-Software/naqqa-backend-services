package com.naqqa.elasticsearch.search.aggs.metrics;

import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.ReduceContext;
import com.naqqa.elasticsearch.search.aggs.SingleValueMetric;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InternalMin extends InternalAggregation implements SingleValueMetric {

    private final double min;

    public InternalMin(String name, double min, Map<String, Object> metadata) {
        super(name, metadata);
        this.min = min;
    }

    @Override
    public double value() {
        return min;
    }

    @Override
    public String getType() {
        return "min";
    }

    @Override
    public InternalAggregation reduce(List<InternalAggregation> aggregations, ReduceContext context) {
        double acc = Double.POSITIVE_INFINITY;
        for (InternalAggregation a : aggregations) {
            acc = Math.min(acc, ((InternalMin) a).min);
        }
        return new InternalMin(getName(), acc, getMetadata());
    }

    @Override
    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("value", Double.isInfinite(min) ? null : min);
        return map;
    }
}
