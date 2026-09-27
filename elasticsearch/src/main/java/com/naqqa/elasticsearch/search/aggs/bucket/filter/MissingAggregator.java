package com.naqqa.elasticsearch.search.aggs.bucket.filter;

import com.naqqa.elasticsearch.search.aggs.AggParseContext;
import com.naqqa.elasticsearch.search.aggs.Aggregator;
import com.naqqa.elasticsearch.search.aggs.support.ParamsHelper;
import com.naqqa.elasticsearch.search.aggs.support.SortedSetValues;

import java.util.function.IntPredicate;

public final class MissingAggregator {

    private MissingAggregator() {
    }

    public static Aggregator parse(AggParseContext ctx) {
        String field = ParamsHelper.requireString(ctx.params(), "field");
        String fieldType = ParamsHelper.getString(ctx.params(), "field_type", "keyword");
        IntPredicate predicate;
        if ("numeric".equals(fieldType)) {
            var source = ctx.lookup().longValues(field);
            predicate = doc -> !source.advanceExact(doc) || source.docValueCount() == 0;
        } else {
            SortedSetValues source = ctx.lookup().bytesValues(field);
            predicate = doc -> !source.advanceExact(doc) || source.docValueCount() == 0;
        }
        return new PredicateBucketAggregator(ctx.name(), "missing", ctx.subAggregators(), ctx.bucketConsumer(), predicate);
    }
}
