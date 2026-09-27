package com.naqqa.elasticsearch.search.aggs.metrics;

import com.naqqa.elasticsearch.search.aggs.AggParseContext;
import com.naqqa.elasticsearch.search.aggs.Aggregator;
import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.support.BucketArrays;
import com.naqqa.elasticsearch.search.aggs.support.DoubleValuesSource;
import com.naqqa.elasticsearch.search.aggs.support.ParamsHelper;

public final class ValueCountAggregator extends Aggregator {

    private final DoubleValuesSource source;
    private long[] counts = new long[4];

    public ValueCountAggregator(String name, DoubleValuesSource source) {
        super(name, new Aggregator[0]);
        this.source = source;
    }

    public static Aggregator parse(AggParseContext ctx) {
        String field = ParamsHelper.requireString(ctx.params(), "field");
        return new ValueCountAggregator(ctx.name(), ctx.lookup().doubleValues(field));
    }

    @Override
    public void collect(int doc, long bucketOrd) {
        if (!source.advanceExact(doc)) {
            return;
        }
        counts = BucketArrays.grow(counts, (int) bucketOrd + 1);
        counts[(int) bucketOrd] += source.docValueCount();
    }

    @Override
    public InternalAggregation buildAggregation(long bucketOrd) {
        long v = bucketOrd >= 0 && bucketOrd < counts.length ? counts[(int) bucketOrd] : 0;
        return new InternalValueCount(name, v, null);
    }
}
