package com.naqqa.elasticsearch.search.aggs.metrics;

import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.ReduceContext;
import com.naqqa.elasticsearch.search.aggs.SingleValueMetric;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InternalMax extends InternalAggregation implements SingleValueMetric {

    private final double max;

    public InternalMax(String name, double max, Map<String, Object> metadata) {
        super(name, metadata);
        this.max = max;
    }

    @Override
    public double value() {
        return max;
    }

    @Override
    public String getType() {
        return "max";
    }

    @Override
    public InternalAggregation reduce(List<InternalAggregation> aggregations, ReduceContext context) {
        double acc = Double.NEGATIVE_INFINITY;
        for (InternalAggregation a : aggregations) {
            acc = Math.max(acc, ((InternalMax) a).max);
        }
        return new InternalMax(getName(), acc, getMetadata());
    }

    @Override
    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("value", Double.isInfinite(max) ? null : max);
        return map;
    }
}
