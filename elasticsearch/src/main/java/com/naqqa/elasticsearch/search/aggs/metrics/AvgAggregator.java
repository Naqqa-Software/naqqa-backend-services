package com.naqqa.elasticsearch.search.aggs.metrics;

import com.naqqa.elasticsearch.search.aggs.AggParseContext;
import com.naqqa.elasticsearch.search.aggs.Aggregator;
import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.support.BucketArrays;
import com.naqqa.elasticsearch.search.aggs.support.DoubleValuesSource;
import com.naqqa.elasticsearch.search.aggs.support.ParamsHelper;

public final class AvgAggregator extends Aggregator {

    private final DoubleValuesSource source;
    private double[] sums = new double[4];
    private long[] counts = new long[4];

    public AvgAggregator(String name, DoubleValuesSource source) {
        super(name, new Aggregator[0]);
        this.source = source;
    }

    public static Aggregator parse(AggParseContext ctx) {
        String field = ParamsHelper.requireString(ctx.params(), "field");
        return new AvgAggregator(ctx.name(), ctx.lookup().doubleValues(field));
    }

    @Override
    public void collect(int doc, long bucketOrd) {
        if (!source.advanceExact(doc)) {
            return;
        }
        sums = BucketArrays.grow(sums, (int) bucketOrd + 1);
        counts = BucketArrays.grow(counts, (int) bucketOrd + 1);
        int n = source.docValueCount();
        for (int i = 0; i < n; i++) {
            sums[(int) bucketOrd] += source.nextValue();
            counts[(int) bucketOrd]++;
        }
    }

    @Override
    public InternalAggregation buildAggregation(long bucketOrd) {
        if (bucketOrd < 0 || bucketOrd >= sums.length) {
            return new InternalAvg(name, 0, 0, null);
        }
        return new InternalAvg(name, sums[(int) bucketOrd], counts[(int) bucketOrd], null);
    }
}
