package com.naqqa.elasticsearch.search.aggs.metrics;

import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.ReduceContext;
import com.naqqa.elasticsearch.search.aggs.SingleValueMetric;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InternalSum extends InternalAggregation implements SingleValueMetric {

    private final double sum;

    public InternalSum(String name, double sum, Map<String, Object> metadata) {
        super(name, metadata);
        this.sum = sum;
    }

    @Override
    public double value() {
        return sum;
    }

    @Override
    public String getType() {
        return "sum";
    }

    @Override
    public InternalAggregation reduce(List<InternalAggregation> aggregations, ReduceContext context) {
        double acc = 0;
        for (InternalAggregation a : aggregations) {
            acc += ((InternalSum) a).sum;
        }
        return new InternalSum(getName(), acc, getMetadata());
    }

    @Override
    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("value", sum);
        return map;
    }
}
