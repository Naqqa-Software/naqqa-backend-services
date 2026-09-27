package com.naqqa.elasticsearch.search.aggs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InternalAggregations extends InternalAggregation {

    public static final InternalAggregations EMPTY = new InternalAggregations(Collections.emptyList());

    private final List<InternalAggregation> aggregations;

    public InternalAggregations(List<InternalAggregation> aggregations) {
        super("_root", null);
        this.aggregations = aggregations;
    }

    public static InternalAggregations from(InternalAggregation[] aggs) {
        return new InternalAggregations(List.of(aggs));
    }

    public List<InternalAggregation> aggregations() {
        return aggregations;
    }

    public InternalAggregation get(String name) {
        for (InternalAggregation agg : aggregations) {
            if (agg.getName().equals(name)) {
                return agg;
            }
        }
        return null;
    }

    @Override
    public String getType() {
        return "aggregations";
    }

    @Override
    public InternalAggregation reduce(List<InternalAggregation> aggregations, ReduceContext context) {
        List<InternalAggregations> casted = new ArrayList<>(aggregations.size());
        for (InternalAggregation agg : aggregations) {
            casted.add((InternalAggregations) agg);
        }
        return reduceAll(casted, context);
    }

    public static InternalAggregations reduceAll(List<InternalAggregations> partials, ReduceContext context) {
        if (partials.isEmpty()) {
            return EMPTY;
        }
        if (partials.size() == 1 && !context.isFinalReduce()) {
            return partials.get(0);
        }
        LinkedHashMap<String, List<InternalAggregation>> byName = new LinkedHashMap<>();
        for (InternalAggregations part : partials) {
            for (InternalAggregation agg : part.aggregations) {
                byName.computeIfAbsent(agg.getName(), k -> new ArrayList<>()).add(agg);
            }
        }
        List<InternalAggregation> reduced = new ArrayList<>(byName.size());
        for (List<InternalAggregation> group : byName.values()) {
            reduced.add(group.get(0).reduce(group, context));
        }
        return new InternalAggregations(reduced);
    }

    @Override
    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (InternalAggregation agg : aggregations) {
            map.put(agg.getName(), agg.toMap());
        }
        return map;
    }
}
