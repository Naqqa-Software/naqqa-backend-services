package com.naqqa.elasticsearch.search.aggs.metrics;

import com.naqqa.elasticsearch.common.geo.GeoPoint;
import com.naqqa.elasticsearch.search.aggs.AggParseContext;
import com.naqqa.elasticsearch.search.aggs.Aggregator;
import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.support.BucketArrays;
import com.naqqa.elasticsearch.search.aggs.support.GeoPointValuesSource;
import com.naqqa.elasticsearch.search.aggs.support.ParamsHelper;

public final class GeoCentroidAggregator extends Aggregator {

    private final GeoPointValuesSource source;
    private double[] latSums = new double[4];
    private double[] lonSums = new double[4];
    private long[] counts = new long[4];

    public GeoCentroidAggregator(String name, GeoPointValuesSource source) {
        super(name, new Aggregator[0]);
        this.source = source;
    }

    public static Aggregator parse(AggParseContext ctx) {
        String field = ParamsHelper.requireString(ctx.params(), "field");
        return new GeoCentroidAggregator(ctx.name(), ctx.lookup().geoPointValues(field));
    }

    @Override
    public void collect(int doc, long bucketOrd) {
        if (!source.advanceExact(doc)) {
            return;
        }
        int minSize = (int) bucketOrd + 1;
        latSums = BucketArrays.grow(latSums, minSize);
        lonSums = BucketArrays.grow(lonSums, minSize);
        counts = BucketArrays.grow(counts, minSize);
        int b = (int) bucketOrd;
        int n = source.docValueCount();
        for (int i = 0; i < n; i++) {
            GeoPoint p = source.nextValue();
            latSums[b] += p.lat();
            lonSums[b] += p.lon();
            counts[b]++;
        }
    }

    @Override
    public InternalAggregation buildAggregation(long bucketOrd) {
        int b = (int) bucketOrd;
        if (bucketOrd < 0 || b >= counts.length) {
            return new InternalGeoCentroid(name, 0, 0, 0, null);
        }
        return new InternalGeoCentroid(name, latSums[b], lonSums[b], counts[b], null);
    }
}
