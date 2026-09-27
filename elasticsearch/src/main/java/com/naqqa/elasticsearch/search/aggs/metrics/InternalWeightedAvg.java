package com.naqqa.elasticsearch.search.aggs.metrics;

import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.ReduceContext;
import com.naqqa.elasticsearch.search.aggs.SingleValueMetric;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InternalWeightedAvg extends InternalAggregation implements SingleValueMetric {

    private final double weightedValueSum;
    private final double weightSum;

    public InternalWeightedAvg(String name, double weightedValueSum, double weightSum, Map<String, Object> metadata) {
        super(name, metadata);
        this.weightedValueSum = weightedValueSum;
        this.weightSum = weightSum;
    }

    @Override
    public double value() {
        return weightSum == 0 ? Double.NaN : weightedValueSum / weightSum;
    }

    @Override
    public String getType() {
        return "weighted_avg";
    }

    @Override
    public InternalAggregation reduce(List<InternalAggregation> aggregations, ReduceContext context) {
        double vAcc = 0;
        double wAcc = 0;
        for (InternalAggregation a : aggregations) {
            InternalWeightedAvg w = (InternalWeightedAvg) a;
            vAcc += w.weightedValueSum;
            wAcc += w.weightSum;
        }
        return new InternalWeightedAvg(getName(), vAcc, wAcc, getMetadata());
    }

    @Override
    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        double v = value();
        map.put("value", Double.isNaN(v) ? null : v);
        return map;
    }
}
