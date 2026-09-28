package com.naqqa.elasticsearch.search.aggs.metrics;

import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.ReduceContext;
import com.naqqa.elasticsearch.search.aggs.support.sketch.Boxplot;
import com.naqqa.elasticsearch.search.aggs.support.sketch.TDigest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InternalBoxplot extends InternalAggregation {

    private final TDigest digest;

    public InternalBoxplot(String name, TDigest digest, Map<String, Object> metadata) {
        super(name, metadata);
        this.digest = digest;
    }

    public Boxplot.Stats stats() {
        return Boxplot.compute(digest);
    }

    public TDigest digestValue() {
        return digest;
    }

    @Override
    public String getType() {
        return "boxplot";
    }

    @Override
    public InternalAggregation reduce(List<InternalAggregation> aggregations, ReduceContext context) {
        TDigest merged = new TDigest(digest.compression());
        for (InternalAggregation a : aggregations) {
            merged.add(((InternalBoxplot) a).digest);
        }
        return new InternalBoxplot(getName(), merged, getMetadata());
    }

    @Override
    public Map<String, Object> toMap() {
        Boxplot.Stats s = stats();
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("min", Double.isInfinite(s.min()) ? null : s.min());
        map.put("max", Double.isInfinite(s.max()) ? null : s.max());
        map.put("q1", Double.isNaN(s.q1()) ? null : s.q1());
        map.put("q2", Double.isNaN(s.q2()) ? null : s.q2());
        map.put("q3", Double.isNaN(s.q3()) ? null : s.q3());
        map.put("lower", Double.isNaN(s.lower()) ? null : s.lower());
        map.put("upper", Double.isNaN(s.upper()) ? null : s.upper());
        return map;
    }
}
