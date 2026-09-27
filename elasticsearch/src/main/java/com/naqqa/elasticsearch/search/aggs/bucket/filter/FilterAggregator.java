package com.naqqa.elasticsearch.search.aggs.bucket.filter;

import com.naqqa.elasticsearch.search.aggs.AggParseContext;
import com.naqqa.elasticsearch.search.aggs.Aggregator;

import java.util.function.IntPredicate;

public final class FilterAggregator {

    private FilterAggregator() {
    }

    @SuppressWarnings("unchecked")
    public static Aggregator parse(AggParseContext ctx) {
        IntPredicate filter = (IntPredicate) ctx.params().get("filter");
        if (filter == null) {
            filter = doc -> true;
        }
        return new PredicateBucketAggregator(ctx.name(), "filter", ctx.subAggregators(), ctx.bucketConsumer(), filter);
    }
}
