package com.naqqa.elasticsearch.search.aggs.metrics;

import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.ReduceContext;
import com.naqqa.elasticsearch.search.aggs.SingleValueMetric;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InternalValueCount extends InternalAggregation implements SingleValueMetric {

    private final long count;

    public InternalValueCount(String name, long count, Map<String, Object> metadata) {
        super(name, metadata);
        this.count = count;
    }

    public long count() {
        return count;
    }

    @Override
    public double value() {
        return count;
    }

    @Override
    public String getType() {
        return "value_count";
    }

    @Override
    public InternalAggregation reduce(List<InternalAggregation> aggregations, ReduceContext context) {
        long acc = 0;
        for (InternalAggregation a : aggregations) {
            acc += ((InternalValueCount) a).count;
        }
        return new InternalValueCount(getName(), acc, getMetadata());
    }

    @Override
    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("value", count);
        return map;
    }
}
