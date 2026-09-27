package com.naqqa.elasticsearch.search.aggs.metrics;

import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.ReduceContext;
import com.naqqa.elasticsearch.search.aggs.SingleValueMetric;
import com.naqqa.elasticsearch.search.aggs.support.sketch.TTest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InternalTTest extends InternalAggregation implements SingleValueMetric {

    private final TTest test;

    public InternalTTest(String name, TTest test, Map<String, Object> metadata) {
        super(name, metadata);
        this.test = test;
    }

    @Override
    public double value() {
        return test.pValue();
    }

    @Override
    public String getType() {
        return "t_test";
    }

    @Override
    public InternalAggregation reduce(List<InternalAggregation> aggregations, ReduceContext context) {
        TTest merged = new TTest(test.type(), test.tails());
        for (InternalAggregation a : aggregations) {
            merged.merge(((InternalTTest) a).test);
        }
        return new InternalTTest(getName(), merged, getMetadata());
    }

    @Override
    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        double v = value();
        map.put("value", Double.isNaN(v) ? null : v);
        return map;
    }
}
