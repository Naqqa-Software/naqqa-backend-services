package com.naqqa.elasticsearch.search.aggs.metrics;

import com.naqqa.elasticsearch.search.aggs.AggParseContext;
import com.naqqa.elasticsearch.search.aggs.Aggregator;
import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.support.BucketArrays;
import com.naqqa.elasticsearch.search.aggs.support.DoubleValuesSource;
import com.naqqa.elasticsearch.search.aggs.support.ParamsHelper;
import com.naqqa.elasticsearch.search.aggs.support.sketch.TDigest;

public final class MedianAbsoluteDeviationAggregator extends Aggregator {

    private final DoubleValuesSource source;
    private final double compression;
    private TDigest[] digests = new TDigest[4];

    public MedianAbsoluteDeviationAggregator(String name, DoubleValuesSource source, double compression) {
        super(name, new Aggregator[0]);
        this.source = source;
        this.compression = compression;
    }

    public static Aggregator parse(AggParseContext ctx) {
        String field = ParamsHelper.requireString(ctx.params(), "field");
        double compression = ParamsHelper.getDouble(ctx.params(), "compression", TDigest.DEFAULT_COMPRESSION);
        return new MedianAbsoluteDeviationAggregator(ctx.name(), ctx.lookup().doubleValues(field), compression);
    }

    @Override
    public void collect(int doc, long bucketOrd) {
        if (!source.advanceExact(doc)) {
            return;
        }
        digests = BucketArrays.grow(digests, (int) bucketOrd + 1);
        int b = (int) bucketOrd;
        if (digests[b] == null) {
            digests[b] = new TDigest(compression);
        }
        int n = source.docValueCount();
        for (int i = 0; i < n; i++) {
            digests[b].add(source.nextValue());
        }
    }

    @Override
    public InternalAggregation buildAggregation(long bucketOrd) {
        int b = (int) bucketOrd;
        TDigest digest = (bucketOrd >= 0 && b < digests.length && digests[b] != null) ? digests[b] : new TDigest(compression);
        return new InternalMedianAbsoluteDeviation(name, digest, null);
    }
}
