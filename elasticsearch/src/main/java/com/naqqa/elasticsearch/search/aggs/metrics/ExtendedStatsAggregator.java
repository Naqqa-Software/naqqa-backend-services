package com.naqqa.elasticsearch.search.aggs.metrics;

import com.naqqa.elasticsearch.search.aggs.AggParseContext;
import com.naqqa.elasticsearch.search.aggs.Aggregator;
import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.support.BucketArrays;
import com.naqqa.elasticsearch.search.aggs.support.DoubleValuesSource;
import com.naqqa.elasticsearch.search.aggs.support.ParamsHelper;

public final class ExtendedStatsAggregator extends StatsAggregator {

    private final double sigma;
    private double[] sumOfSquares = new double[4];

    public ExtendedStatsAggregator(String name, DoubleValuesSource source, double sigma) {
        super(name, source);
        this.sigma = sigma;
    }

    public static Aggregator parse(AggParseContext ctx) {
        String field = ParamsHelper.requireString(ctx.params(), "field");
        double sigma = ParamsHelper.getDouble(ctx.params(), "sigma", 2.0);
        return new ExtendedStatsAggregator(ctx.name(), ctx.lookup().doubleValues(field), sigma);
    }

    @Override
    public void collect(int doc, long bucketOrd) {
        if (!source.advanceExact(doc)) {
            return;
        }
        grow((int) bucketOrd + 1);
        sumOfSquares = BucketArrays.grow(sumOfSquares, (int) bucketOrd + 1);
        int b = (int) bucketOrd;
        int n = source.docValueCount();
        for (int i = 0; i < n; i++) {
            double v = source.nextValue();
            counts[b]++;
            sums[b] += v;
            sumOfSquares[b] += v * v;
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
            return new InternalExtendedStats(name, 0, 0, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0, sigma, null);
        }
        return new InternalExtendedStats(name, counts[b], sums[b], mins[b], maxs[b], sumOfSquares[b], sigma, null);
    }
}
