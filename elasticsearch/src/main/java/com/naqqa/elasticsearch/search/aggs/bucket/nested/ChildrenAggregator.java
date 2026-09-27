package com.naqqa.elasticsearch.search.aggs.bucket.nested;

import com.naqqa.elasticsearch.search.aggs.AggParseContext;
import com.naqqa.elasticsearch.search.aggs.Aggregator;

import java.util.function.IntFunction;

public final class ChildrenAggregator {

    private ChildrenAggregator() {
    }

    @SuppressWarnings("unchecked")
    public static Aggregator parse(AggParseContext ctx) {
        IntFunction<int[]> childrenOf = (IntFunction<int[]>) ctx.params().get("children_of");
        return new RelatedDocsAggregator(ctx.name(), "children", ctx.subAggregators(), ctx.bucketConsumer(), childrenOf);
    }
}
