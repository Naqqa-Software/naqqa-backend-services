package com.naqqa.elasticsearch.search.aggs;

import java.util.List;
import java.util.Map;

public abstract class InternalAggregation {

    private final String name;
    private final Map<String, Object> metadata;

    protected InternalAggregation(String name, Map<String, Object> metadata) {
        this.name = name;
        this.metadata = metadata;
    }

    public final String getName() {
        return name;
    }

    public final Map<String, Object> getMetadata() {
        return metadata;
    }

    public abstract String getType();

    public abstract InternalAggregation reduce(List<InternalAggregation> aggregations, ReduceContext context);

    public abstract Map<String, Object> toMap();
}
