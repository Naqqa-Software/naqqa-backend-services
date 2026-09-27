package com.naqqa.elasticsearch.search.aggs.bucket.geo;

import com.naqqa.elasticsearch.common.geo.GeoDistance;
import com.naqqa.elasticsearch.common.geo.GeoPoint;
import com.naqqa.elasticsearch.common.geo.GeoPointParser;
import com.naqqa.elasticsearch.common.geo.DistanceUnit;
import com.naqqa.elasticsearch.search.aggs.AggParseContext;
import com.naqqa.elasticsearch.search.aggs.Aggregator;
import com.naqqa.elasticsearch.search.aggs.BucketsAggregator;
import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.InternalAggregations;
import com.naqqa.elasticsearch.search.aggs.bucket.range.InternalRange;
import com.naqqa.elasticsearch.search.aggs.bucket.range.RangeAggregator;
import com.naqqa.elasticsearch.search.aggs.bucket.range.RangeBucket;
import com.naqqa.elasticsearch.search.aggs.bucket.range.RangeSpec;
import com.naqqa.elasticsearch.search.aggs.support.GeoPointValuesSource;
import com.naqqa.elasticsearch.search.aggs.support.MultiBucketConsumer;
import com.naqqa.elasticsearch.search.aggs.support.ParamsHelper;

import java.util.ArrayList;
import java.util.List;

public final class GeoDistanceAggregator extends BucketsAggregator {

    private final GeoPointValuesSource source;
    private final GeoPoint origin;
    private final GeoDistance distanceType;
    private final DistanceUnit unit;
    private final List<RangeSpec> ranges;

    public GeoDistanceAggregator(String name, Aggregator[] subAggregators, MultiBucketConsumer bucketConsumer, GeoPointValuesSource source,
                                  GeoPoint origin, GeoDistance distanceType, DistanceUnit unit, List<RangeSpec> ranges) {
        super(name, subAggregators, bucketConsumer);
        this.source = source;
        this.origin = origin;
        this.distanceType = distanceType;
        this.unit = unit;
        this.ranges = ranges;
    }

    public static Aggregator parse(AggParseContext ctx) {
        String field = ParamsHelper.requireString(ctx.params(), "field");
        GeoPoint origin = GeoPointParser.parse(ctx.params().get("origin"), true);
        GeoDistance distanceType = GeoDistance.fromString(ParamsHelper.getString(ctx.params(), "distance_type", "arc"));
        DistanceUnit unit = DistanceUnit.fromString(ParamsHelper.getString(ctx.params(), "unit", "m"));
        List<RangeSpec> ranges = RangeAggregator.parseRanges(ctx);
        return new GeoDistanceAggregator(ctx.name(), ctx.subAggregators(), ctx.bucketConsumer(), ctx.lookup().geoPointValues(field),
            origin, distanceType, unit, ranges);
    }

    @Override
    public void collect(int doc, long bucketOrd) {
        if (!source.advanceExact(doc)) {
            return;
        }
        int n = source.docValueCount();
        for (int i = 0; i < n; i++) {
            GeoPoint p = source.nextValue();
            double distance = distanceType.calculate(origin, p, unit);
            for (int r = 0; r < ranges.size(); r++) {
                if (ranges.get(r).matches(distance)) {
                    collectBucket(doc, bucketOrd, r);
                }
            }
        }
    }

    @Override
    public InternalAggregation buildAggregation(long owningBucketOrd) {
        List<RangeBucket> buckets = new ArrayList<>();
        for (int r = 0; r < ranges.size(); r++) {
            long bucketOrd = bucketOrds.find(owningBucketOrd, r);
            long docCount = bucketDocCount(bucketOrd);
            InternalAggregations subAggs = buildSubAggsForBucket(bucketOrd);
            buckets.add(new RangeBucket(ranges.get(r), docCount, subAggs));
        }
        return new InternalRange(name, buckets, null);
    }
}
