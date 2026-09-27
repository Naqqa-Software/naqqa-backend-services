package com.naqqa.elasticsearch.search.aggs.metrics;

import com.naqqa.elasticsearch.search.aggs.AggParseContext;
import com.naqqa.elasticsearch.search.aggs.Aggregator;
import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.support.BucketArrays;
import com.naqqa.elasticsearch.search.aggs.support.DoubleValuesSource;
import com.naqqa.elasticsearch.search.aggs.support.ParamsHelper;

public final class MaxAggregator extends Aggregator {

    private final DoubleValuesSource source;
    private double[] maxs = new double[4];

    public MaxAggregator(String name, DoubleValuesSource source) {
        super(name, new Aggregator[0]);
        this.source = source;
        java.util.Arrays.fill(maxs, Double.NEGATIVE_INFINITY);
    }

    public static Aggregator parse(AggParseContext ctx) {
        String field = ParamsHelper.requireString(ctx.params(), "field");
        return new MaxAggregator(ctx.name(), ctx.lookup().doubleValues(field));
    }

    @Override
    public void collect(int doc, long bucketOrd) {
        if (!source.advanceExact(doc)) {
            return;
        }
        int oldLen = maxs.length;
        maxs = BucketArrays.grow(maxs, (int) bucketOrd + 1);
        if (maxs.length > oldLen) {
            java.util.Arrays.fill(maxs, oldLen, maxs.length, Double.NEGATIVE_INFINITY);
        }
        int n = source.docValueCount();
        for (int i = 0; i < n; i++) {
            double v = source.nextValue();
            if (v > maxs[(int) bucketOrd]) {
                maxs[(int) bucketOrd] = v;
            }
        }
    }

    @Override
    public InternalAggregation buildAggregation(long bucketOrd) {
        double v = bucketOrd >= 0 && bucketOrd < maxs.length ? maxs[(int) bucketOrd] : Double.NEGATIVE_INFINITY;
        return new InternalMax(name, v, null);
    }
}
