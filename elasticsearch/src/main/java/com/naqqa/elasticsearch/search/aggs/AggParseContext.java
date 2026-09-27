package com.naqqa.elasticsearch.search.aggs;

import com.naqqa.elasticsearch.search.aggs.support.MultiBucketConsumer;
import com.naqqa.elasticsearch.search.aggs.support.ValuesLookup;

import java.util.Map;

public final class AggParseContext {

    private final String name;
    private final Map<String, Object> params;
    private final Aggregator[] subAggregators;
    private final ValuesLookup lookup;
    private final MultiBucketConsumer bucketConsumer;
    private final Map<String, Object> metadata;

    public AggParseContext(String name, Map<String, Object> params, Aggregator[] subAggregators, ValuesLookup lookup,
                            MultiBucketConsumer bucketConsumer, Map<String, Object> metadata) {
        this.name = name;
        this.params = params;
        this.subAggregators = subAggregators;
        this.lookup = lookup;
        this.bucketConsumer = bucketConsumer;
        this.metadata = metadata;
    }

    public String name() {
        return name;
    }

    public Map<String, Object> params() {
        return params;
    }

    public Aggregator[] subAggregators() {
        return subAggregators;
    }

    public ValuesLookup lookup() {
        return lookup;
    }

    public MultiBucketConsumer bucketConsumer() {
        return bucketConsumer;
    }

    public Map<String, Object> metadata() {
        return metadata;
    }
}
