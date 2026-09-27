package com.naqqa.elasticsearch.search.aggs.metrics;

import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.ReduceContext;
import com.naqqa.elasticsearch.search.aggs.SingleValueMetric;
import com.naqqa.elasticsearch.search.aggs.support.sketch.HyperLogLogPlusPlus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InternalCardinality extends InternalAggregation implements SingleValueMetric {

    private final HyperLogLogPlusPlus counts;

    public InternalCardinality(String name, HyperLogLogPlusPlus counts, Map<String, Object> metadata) {
        super(name, metadata);
        this.counts = counts;
    }

    public long cardinality() {
        return counts.cardinality(0);
    }

    public HyperLogLogPlusPlus counts() {
        return counts;
    }

    @Override
    public double value() {
        return cardinality();
    }

    @Override
    public String getType() {
        return "cardinality";
    }

    @Override
    public InternalAggregation reduce(List<InternalAggregation> aggregations, ReduceContext context) {
        HyperLogLogPlusPlus merged = new HyperLogLogPlusPlus(counts.precision(), 1);
        for (InternalAggregation a : aggregations) {
            InternalCardinality c = (InternalCardinality) a;
            merged.merge(0, c.counts, 0);
        }
        return new InternalCardinality(getName(), merged, getMetadata());
    }

    @Override
    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("value", cardinality());
        return map;
    }
}
