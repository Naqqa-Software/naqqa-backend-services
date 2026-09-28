package com.naqqa.elasticsearch.search.aggs.metrics;

import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.ReduceContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InternalTopMetrics extends InternalAggregation {

    public record TopMetric(double sortValue, Map<String, Double> metrics) {
    }

    private final int size;
    private final boolean ascending;
    private final List<TopMetric> topMetrics;

    public InternalTopMetrics(String name, int size, boolean ascending, List<TopMetric> topMetrics, Map<String, Object> metadata) {
        super(name, metadata);
        this.size = size;
        this.ascending = ascending;
        this.topMetrics = topMetrics;
    }

    public List<TopMetric> topMetrics() {
        return topMetrics;
    }

    public int sizeValue() {
        return size;
    }

    public boolean ascendingValue() {
        return ascending;
    }

    @Override
    public String getType() {
        return "top_metrics";
    }

    @Override
    public InternalAggregation reduce(List<InternalAggregation> aggregations, ReduceContext context) {
        List<TopMetric> merged = new ArrayList<>();
        for (InternalAggregation a : aggregations) {
            merged.addAll(((InternalTopMetrics) a).topMetrics);
        }
        Comparator<TopMetric> cmp = Comparator.comparingDouble(TopMetric::sortValue);
        if (!ascending) {
            cmp = cmp.reversed();
        }
        merged.sort(cmp);
        if (merged.size() > size) {
            merged = new ArrayList<>(merged.subList(0, size));
        }
        return new InternalTopMetrics(getName(), size, ascending, merged, getMetadata());
    }

    @Override
    public Map<String, Object> toMap() {
        List<Object> list = new ArrayList<>();
        for (TopMetric m : topMetrics) {
            LinkedHashMap<String, Object> entry = new LinkedHashMap<>();
            entry.put("sort", List.of(m.sortValue()));
            entry.put("metrics", m.metrics());
            list.add(entry);
        }
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("top", list);
        return map;
    }
}
