package com.naqqa.elasticsearch.search.aggs.metrics;

import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.ReduceContext;
import com.naqqa.elasticsearch.search.aggs.SingleValueMetric;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InternalRate extends InternalAggregation implements SingleValueMetric {

    private final double sum;
    private final double divisor;

    public InternalRate(String name, double sum, double divisor, Map<String, Object> metadata) {
        super(name, metadata);
        this.sum = sum;
        this.divisor = divisor;
    }

    @Override
    public double value() {
        return divisor == 0 ? Double.NaN : sum / divisor;
    }

    public double sumValue() {
        return sum;
    }

    public double divisorValue() {
        return divisor;
    }

    @Override
    public String getType() {
        return "rate";
    }

    @Override
    public InternalAggregation reduce(List<InternalAggregation> aggregations, ReduceContext context) {
        double acc = 0;
        for (InternalAggregation a : aggregations) {
            acc += ((InternalRate) a).sum;
        }
        return new InternalRate(getName(), acc, divisor, getMetadata());
    }

    @Override
    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("value", value());
        return map;
    }
}
