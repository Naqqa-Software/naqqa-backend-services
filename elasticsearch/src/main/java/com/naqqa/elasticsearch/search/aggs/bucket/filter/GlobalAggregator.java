package com.naqqa.elasticsearch.search.aggs.bucket.filter;

import com.naqqa.elasticsearch.search.aggs.AggParseContext;
import com.naqqa.elasticsearch.search.aggs.Aggregator;

public final class GlobalAggregator {

    private GlobalAggregator() {
    }

    public static Aggregator parse(AggParseContext ctx) {
        return new PredicateBucketAggregator(ctx.name(), "global", ctx.subAggregators(), ctx.bucketConsumer(), doc -> true);
    }
}
