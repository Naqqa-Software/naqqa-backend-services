package com.naqqa.elasticsearch.search.aggs.metrics;

import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.ReduceContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class InternalStats extends InternalAggregation {

    protected final long count;
    protected final double sum;
    protected final double min;
    protected final double max;

    public InternalStats(String name, long count, double sum, double min, double max, Map<String, Object> metadata) {
        super(name, metadata);
        this.count = count;
        this.sum = sum;
        this.min = min;
        this.max = max;
    }

    public long count() {
        return count;
    }

    public double sum() {
        return sum;
    }

    public double min() {
        return min;
    }

    public double max() {
        return max;
    }

    public double avg() {
        return count == 0 ? Double.NaN : sum / count;
    }

    @Override
    public String getType() {
        return "stats";
    }

    @Override
    public InternalAggregation reduce(List<InternalAggregation> aggregations, ReduceContext context) {
        long countAcc = 0;
        double sumAcc = 0;
        double minAcc = Double.POSITIVE_INFINITY;
        double maxAcc = Double.NEGATIVE_INFINITY;
        for (InternalAggregation a : aggregations) {
            InternalStats s = (InternalStats) a;
            countAcc += s.count;
            sumAcc += s.sum;
            minAcc = Math.min(minAcc, s.min);
            maxAcc = Math.max(maxAcc, s.max);
        }
        return new InternalStats(getName(), countAcc, sumAcc, minAcc, maxAcc, getMetadata());
    }

    @Override
    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("count", count);
        map.put("min", count == 0 ? null : min);
        map.put("max", count == 0 ? null : max);
        map.put("avg", count == 0 ? null : avg());
        map.put("sum", sum);
        return map;
    }
}
