package com.naqqa.elasticsearch.search.aggs.metrics;

import com.naqqa.elasticsearch.common.geo.GeoPoint;
import com.naqqa.elasticsearch.search.aggs.AggParseContext;
import com.naqqa.elasticsearch.search.aggs.Aggregator;
import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.support.BucketArrays;
import com.naqqa.elasticsearch.search.aggs.support.GeoPointValuesSource;
import com.naqqa.elasticsearch.search.aggs.support.ParamsHelper;

import java.util.Arrays;

public final class GeoBoundsAggregator extends Aggregator {

    private final GeoPointValuesSource source;
    private double[] tops = new double[4];
    private double[] bottoms = new double[4];
    private double[] lefts = new double[4];
    private double[] rights = new double[4];

    public GeoBoundsAggregator(String name, GeoPointValuesSource source) {
        super(name, new Aggregator[0]);
        this.source = source;
        Arrays.fill(tops, Double.NEGATIVE_INFINITY);
        Arrays.fill(bottoms, Double.POSITIVE_INFINITY);
        Arrays.fill(lefts, Double.POSITIVE_INFINITY);
        Arrays.fill(rights, Double.NEGATIVE_INFINITY);
    }

    public static Aggregator parse(AggParseContext ctx) {
        String field = ParamsHelper.requireString(ctx.params(), "field");
        return new GeoBoundsAggregator(ctx.name(), ctx.lookup().geoPointValues(field));
    }

    @Override
    public void collect(int doc, long bucketOrd) {
        if (!source.advanceExact(doc)) {
            return;
        }
        int oldLen = tops.length;
        int minSize = (int) bucketOrd + 1;
        tops = BucketArrays.grow(tops, minSize);
        bottoms = BucketArrays.grow(bottoms, minSize);
        lefts = BucketArrays.grow(lefts, minSize);
        rights = BucketArrays.grow(rights, minSize);
        if (tops.length > oldLen) {
            Arrays.fill(tops, oldLen, tops.length, Double.NEGATIVE_INFINITY);
            Arrays.fill(bottoms, oldLen, bottoms.length, Double.POSITIVE_INFINITY);
            Arrays.fill(lefts, oldLen, lefts.length, Double.POSITIVE_INFINITY);
            Arrays.fill(rights, oldLen, rights.length, Double.NEGATIVE_INFINITY);
        }
        int b = (int) bucketOrd;
        int n = source.docValueCount();
        for (int i = 0; i < n; i++) {
            GeoPoint p = source.nextValue();
            if (p.lat() > tops[b]) {
                tops[b] = p.lat();
            }
            if (p.lat() < bottoms[b]) {
                bottoms[b] = p.lat();
            }
            if (p.lon() < lefts[b]) {
                lefts[b] = p.lon();
            }
            if (p.lon() > rights[b]) {
                rights[b] = p.lon();
            }
        }
    }

    @Override
    public InternalAggregation buildAggregation(long bucketOrd) {
        int b = (int) bucketOrd;
        if (bucketOrd < 0 || b >= tops.length) {
            return new InternalGeoBounds(name, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, null);
        }
        return new InternalGeoBounds(name, tops[b], bottoms[b], lefts[b], rights[b], null);
    }
}
