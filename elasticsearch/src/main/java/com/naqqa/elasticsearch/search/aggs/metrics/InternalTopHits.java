package com.naqqa.elasticsearch.search.aggs.metrics;

import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.ReduceContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InternalTopHits extends InternalAggregation {

    public record Hit(double sortValue, Map<String, Object> source) {
    }

    private final int size;
    private final boolean ascending;
    private final List<Hit> hits;

    public InternalTopHits(String name, int size, boolean ascending, List<Hit> hits, Map<String, Object> metadata) {
        super(name, metadata);
        this.size = size;
        this.ascending = ascending;
        this.hits = hits;
    }

    public List<Hit> hits() {
        return hits;
    }

    public int sizeValue() {
        return size;
    }

    public boolean ascendingValue() {
        return ascending;
    }

    @Override
    public String getType() {
        return "top_hits";
    }

    @Override
    public InternalAggregation reduce(List<InternalAggregation> aggregations, ReduceContext context) {
        List<Hit> merged = new ArrayList<>();
        for (InternalAggregation a : aggregations) {
            merged.addAll(((InternalTopHits) a).hits);
        }
        Comparator<Hit> cmp = Comparator.comparingDouble(Hit::sortValue);
        if (!ascending) {
            cmp = cmp.reversed();
        }
        merged.sort(cmp);
        if (merged.size() > size) {
            merged = new ArrayList<>(merged.subList(0, size));
        }
        return new InternalTopHits(getName(), size, ascending, merged, getMetadata());
    }

    @Override
    public Map<String, Object> toMap() {
        List<Object> hitList = new ArrayList<>();
        for (Hit h : hits) {
            LinkedHashMap<String, Object> hitMap = new LinkedHashMap<>();
            hitMap.put("_source", h.source());
            hitMap.put("sort", List.of(h.sortValue()));
            hitList.add(hitMap);
        }
        LinkedHashMap<String, Object> total = new LinkedHashMap<>();
        total.put("value", hits.size());
        LinkedHashMap<String, Object> inner = new LinkedHashMap<>();
        inner.put("total", total);
        inner.put("hits", hitList);
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("hits", inner);
        return map;
    }
}
