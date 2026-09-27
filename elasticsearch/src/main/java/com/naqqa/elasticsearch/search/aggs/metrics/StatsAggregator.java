package com.naqqa.elasticsearch.search.aggs.metrics;

import com.naqqa.elasticsearch.search.aggs.AggParseContext;
import com.naqqa.elasticsearch.search.aggs.Aggregator;
import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.support.BucketArrays;
import com.naqqa.elasticsearch.search.aggs.support.DoubleValuesSource;
import com.naqqa.elasticsearch.search.aggs.support.ParamsHelper;

public class StatsAggregator extends Aggregator {

    protected final DoubleValuesSource source;
    protected long[] counts = new long[4];
    protected double[] sums = new double[4];
    protected double[] mins = new double[4];
    protected double[] maxs = new double[4];

    public StatsAggregator(String name, DoubleValuesSource source) {
        super(name, new Aggregator[0]);
        this.source = source;
        java.util.Arrays.fill(mins, Double.POSITIVE_INFINITY);
        java.util.Arrays.fill(maxs, Double.NEGATIVE_INFINITY);
    }

    public static Aggregator parse(AggParseContext ctx) {
        String field = ParamsHelper.requireString(ctx.params(), "field");
        return new StatsAggregator(ctx.name(), ctx.lookup().doubleValues(field));
    }

    protected void grow(int minSize) {
        int oldLen = mins.length;
        counts = BucketArrays.grow(counts, minSize);
        sums = BucketArrays.grow(sums, minSize);
        mins = BucketArrays.grow(mins, minSize);
        maxs = BucketArrays.grow(maxs, minSize);
        if (mins.length > oldLen) {
            java.util.Arrays.fill(mins, oldLen, mins.length, Double.POSITIVE_INFINITY);
            java.util.Arrays.fill(maxs, oldLen, maxs.length, Double.NEGATIVE_INFINITY);
        }
    }

    @Override
    public void collect(int doc, long bucketOrd) {
        if (!source.advanceExact(doc)) {
            return;
        }
        grow((int) bucketOrd + 1);
        int b = (int) bucketOrd;
        int n = source.docValueCount();
        for (int i = 0; i < n; i++) {
            double v = source.nextValue();
            counts[b]++;
            sums[b] += v;
            if (v < mins[b]) {
                mins[b] = v;
            }
            if (v > maxs[b]) {
                maxs[b] = v;
            }
        }
    }

    @Override
    public InternalAggregation buildAggregation(long bucketOrd) {
        int b = (int) bucketOrd;
        if (bucketOrd < 0 || b >= counts.length) {
            return new InternalStats(name, 0, 0, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, null);
        }
        return new InternalStats(name, counts[b], sums[b], mins[b], maxs[b], null);
    }
}
