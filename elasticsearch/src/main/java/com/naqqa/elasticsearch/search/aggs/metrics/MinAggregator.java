package com.naqqa.elasticsearch.search.aggs.metrics;

import com.naqqa.elasticsearch.search.aggs.AggParseContext;
import com.naqqa.elasticsearch.search.aggs.Aggregator;
import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.support.BucketArrays;
import com.naqqa.elasticsearch.search.aggs.support.DoubleValuesSource;
import com.naqqa.elasticsearch.search.aggs.support.ParamsHelper;

public final class MinAggregator extends Aggregator {

    private final DoubleValuesSource source;
    private double[] mins = new double[4];

    public MinAggregator(String name, DoubleValuesSource source) {
        super(name, new Aggregator[0]);
        this.source = source;
        java.util.Arrays.fill(mins, Double.POSITIVE_INFINITY);
    }

    public static Aggregator parse(AggParseContext ctx) {
        String field = ParamsHelper.requireString(ctx.params(), "field");
        return new MinAggregator(ctx.name(), ctx.lookup().doubleValues(field));
    }

    @Override
    public void collect(int doc, long bucketOrd) {
        if (!source.advanceExact(doc)) {
            return;
        }
        int oldLen = mins.length;
        mins = BucketArrays.grow(mins, (int) bucketOrd + 1);
        if (mins.length > oldLen) {
            java.util.Arrays.fill(mins, oldLen, mins.length, Double.POSITIVE_INFINITY);
        }
        int n = source.docValueCount();
        for (int i = 0; i < n; i++) {
            double v = source.nextValue();
            if (v < mins[(int) bucketOrd]) {
                mins[(int) bucketOrd] = v;
            }
        }
    }

    @Override
    public InternalAggregation buildAggregation(long bucketOrd) {
        double v = bucketOrd >= 0 && bucketOrd < mins.length ? mins[(int) bucketOrd] : Double.POSITIVE_INFINITY;
        return new InternalMin(name, v, null);
    }
}
