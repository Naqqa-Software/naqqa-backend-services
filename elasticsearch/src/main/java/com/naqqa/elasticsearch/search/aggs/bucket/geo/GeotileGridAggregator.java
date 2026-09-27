package com.naqqa.elasticsearch.search.aggs.bucket.geo;

import com.naqqa.elasticsearch.common.geo.GeoPoint;
import com.naqqa.elasticsearch.common.geo.GeoTileUtils;
import com.naqqa.elasticsearch.search.aggs.AggParseContext;
import com.naqqa.elasticsearch.search.aggs.Aggregator;
import com.naqqa.elasticsearch.search.aggs.BucketsAggregator;
import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.InternalAggregations;
import com.naqqa.elasticsearch.search.aggs.bucket.terms.InternalTerms;
import com.naqqa.elasticsearch.search.aggs.bucket.terms.TermsBucket;
import com.naqqa.elasticsearch.search.aggs.bucket.terms.TermsOrder;
import com.naqqa.elasticsearch.search.aggs.support.GeoPointValuesSource;
import com.naqqa.elasticsearch.search.aggs.support.MultiBucketConsumer;
import com.naqqa.elasticsearch.search.aggs.support.ParamsHelper;

import java.util.ArrayList;
import java.util.List;

public final class GeotileGridAggregator extends BucketsAggregator {

    private final GeoPointValuesSource source;
    private final int precision;
    private final int size;
    private final int shardSize;

    public GeotileGridAggregator(String name, Aggregator[] subAggregators, MultiBucketConsumer bucketConsumer, GeoPointValuesSource source,
                                  int precision, int size, int shardSize) {
        super(name, subAggregators, bucketConsumer);
        this.source = source;
        this.precision = precision;
        this.size = size;
        this.shardSize = shardSize;
    }

    public static Aggregator parse(AggParseContext ctx) {
        String field = ParamsHelper.requireString(ctx.params(), "field");
        int precision = ParamsHelper.getInt(ctx.params(), "precision", 7);
        int size = ParamsHelper.getInt(ctx.params(), "size", 10000);
        int shardSize = ParamsHelper.getInt(ctx.params(), "shard_size", Math.max(size, size * 2));
        return new GeotileGridAggregator(ctx.name(), ctx.subAggregators(), ctx.bucketConsumer(), ctx.lookup().geoPointValues(field), precision, size, shardSize);
    }

    @Override
    public void collect(int doc, long bucketOrd) {
        if (!source.advanceExact(doc)) {
            return;
        }
        int n = source.docValueCount();
        for (int i = 0; i < n; i++) {
            GeoPoint p = source.nextValue();
            long tile = GeoTileUtils.longEncode(p.lon(), p.lat(), precision);
            collectBucket(doc, bucketOrd, tile);
        }
    }

    @Override
    public InternalAggregation buildAggregation(long owningBucketOrd) {
        List<TermsBucket> candidates = new ArrayList<>();
        var ords = bucketOrds.ordsFor(owningBucketOrd);
        for (int i = 0; i < ords.size(); i++) {
            long bucketOrd = ords.get(i);
            String key = GeoTileUtils.stringEncode(bucketOrds.key(bucketOrd));
            InternalAggregations subAggs = buildSubAggsForBucket(bucketOrd);
            candidates.add(new TermsBucket(key, bucketDocCount(bucketOrd), 0, subAggs));
        }
        TermsOrder order = TermsOrder.count(false);
        candidates.sort(order.comparator());
        List<TermsBucket> kept = new ArrayList<>();
        for (TermsBucket b : candidates) {
            if (kept.size() < shardSize) {
                kept.add(b);
            }
        }
        return new InternalTerms(name, kept, 0, size, 1, order, false, null);
    }
}
