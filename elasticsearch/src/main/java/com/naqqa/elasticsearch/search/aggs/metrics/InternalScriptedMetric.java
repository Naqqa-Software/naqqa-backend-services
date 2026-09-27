package com.naqqa.elasticsearch.search.aggs.metrics;

import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.ReduceContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class InternalScriptedMetric extends InternalAggregation {

    private final List<Object> values;
    private final Function<List<Object>, Object> reduceFn;

    public InternalScriptedMetric(String name, List<Object> values, Function<List<Object>, Object> reduceFn, Map<String, Object> metadata) {
        super(name, metadata);
        this.values = values;
        this.reduceFn = reduceFn;
    }

    public List<Object> values() {
        return values;
    }

    @Override
    public String getType() {
        return "scripted_metric";
    }

    @Override
    public InternalAggregation reduce(List<InternalAggregation> aggregations, ReduceContext context) {
        List<Object> merged = new ArrayList<>();
        for (InternalAggregation a : aggregations) {
            merged.addAll(((InternalScriptedMetric) a).values);
        }
        if (context.isFinalReduce() && reduceFn != null) {
            merged = new ArrayList<>(java.util.Collections.singletonList(reduceFn.apply(merged)));
        }
        return new InternalScriptedMetric(getName(), merged, reduceFn, getMetadata());
    }

    @Override
    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("value", values.size() == 1 ? values.get(0) : values);
        return map;
    }
}
