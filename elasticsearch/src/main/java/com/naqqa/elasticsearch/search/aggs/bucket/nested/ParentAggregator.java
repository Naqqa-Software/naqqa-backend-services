package com.naqqa.elasticsearch.search.aggs.bucket.nested;

import com.naqqa.elasticsearch.search.aggs.AggParseContext;
import com.naqqa.elasticsearch.search.aggs.Aggregator;

import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;

public final class ParentAggregator {

    private ParentAggregator() {
    }

    @SuppressWarnings("unchecked")
    public static Aggregator parse(AggParseContext ctx) {
        IntUnaryOperator parentOf = (IntUnaryOperator) ctx.params().get("parent_of");
        IntFunction<int[]> mapping = doc -> new int[]{parentOf.applyAsInt(doc)};
        return new RelatedDocsAggregator(ctx.name(), "parent", ctx.subAggregators(), ctx.bucketConsumer(), mapping);
    }
}
