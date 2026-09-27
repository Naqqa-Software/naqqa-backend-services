package com.naqqa.elasticsearch.search.aggs.metrics;

import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.ReduceContext;
import com.naqqa.elasticsearch.search.aggs.SingleValueMetric;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InternalAvg extends InternalAggregation implements SingleValueMetric {

    private final double sum;
    private final long count;

    public InternalAvg(String name, double sum, long count, Map<String, Object> metadata) {
        super(name, metadata);
        this.sum = sum;
        this.count = count;
    }

    public double sum() {
        return sum;
    }

    public long count() {
        return count;
    }

    @Override
    public double value() {
        return count == 0 ? Double.NaN : sum / count;
    }

    @Override
    public String getType() {
        return "avg";
    }

    @Override
    public InternalAggregation reduce(List<InternalAggregation> aggregations, ReduceContext context) {
        double sumAcc = 0;
        long countAcc = 0;
        for (InternalAggregation a : aggregations) {
            InternalAvg avg = (InternalAvg) a;
            sumAcc += avg.sum;
            countAcc += avg.count;
        }
        return new InternalAvg(getName(), sumAcc, countAcc, getMetadata());
    }

    @Override
    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        double v = value();
        map.put("value", Double.isNaN(v) ? null : v);
        return map;
    }
}
