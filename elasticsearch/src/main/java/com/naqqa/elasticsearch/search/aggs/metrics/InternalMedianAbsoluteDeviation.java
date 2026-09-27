package com.naqqa.elasticsearch.search.aggs.metrics;

import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.ReduceContext;
import com.naqqa.elasticsearch.search.aggs.SingleValueMetric;
import com.naqqa.elasticsearch.search.aggs.support.sketch.MedianAbsoluteDeviation;
import com.naqqa.elasticsearch.search.aggs.support.sketch.TDigest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InternalMedianAbsoluteDeviation extends InternalAggregation implements SingleValueMetric {

    private final TDigest digest;

    public InternalMedianAbsoluteDeviation(String name, TDigest digest, Map<String, Object> metadata) {
        super(name, metadata);
        this.digest = digest;
    }

    @Override
    public double value() {
        return MedianAbsoluteDeviation.compute(digest);
    }

    @Override
    public String getType() {
        return "median_absolute_deviation";
    }

    @Override
    public InternalAggregation reduce(List<InternalAggregation> aggregations, ReduceContext context) {
        TDigest merged = new TDigest(digest.compression());
        for (InternalAggregation a : aggregations) {
            merged.add(((InternalMedianAbsoluteDeviation) a).digest);
        }
        return new InternalMedianAbsoluteDeviation(getName(), merged, getMetadata());
    }

    @Override
    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        double v = value();
        map.put("value", Double.isNaN(v) ? null : v);
        return map;
    }
}
