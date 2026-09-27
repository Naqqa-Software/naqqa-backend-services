package com.naqqa.elasticsearch.search.aggs.bucket.histogram;

import com.naqqa.elasticsearch.codec.NumericUtils;
import com.naqqa.elasticsearch.search.aggs.AggParseContext;
import com.naqqa.elasticsearch.search.aggs.Aggregator;
import com.naqqa.elasticsearch.search.aggs.BucketsAggregator;
import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.InternalAggregations;
import com.naqqa.elasticsearch.search.aggs.support.DoubleValuesSource;
import com.naqqa.elasticsearch.search.aggs.support.MultiBucketConsumer;
import com.naqqa.elasticsearch.search.aggs.support.ParamsHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class HistogramAggregator extends BucketsAggregator {

    private final DoubleValuesSource source;
    private final double interval;
    private final double offset;
    private final long minDocCount;
    private final Double boundsMin;
    private final Double boundsMax;

    public HistogramAggregator(String name, Aggregator[] subAggregators, MultiBucketConsumer bucketConsumer, DoubleValuesSource source,
                                double interval, double offset, long minDocCount, Double boundsMin, Double boundsMax) {
        super(name, subAggregators, bucketConsumer);
        this.source = source;
        this.interval = interval;
        this.offset = offset;
        this.minDocCount = minDocCount;
        this.boundsMin = boundsMin;
        this.boundsMax = boundsMax;
    }

    public static Aggregator parse(AggParseContext ctx) {
        String field = ParamsHelper.requireString(ctx.params(), "field");
        double interval = ParamsHelper.getDouble(ctx.params(), "interval", 1.0);
        double offset = ParamsHelper.getDouble(ctx.params(), "offset", 0.0);
        long minDocCount = ParamsHelper.getLong(ctx.params(), "min_doc_count", 1);
        Double boundsMin = null;
        Double boundsMax = null;
        Object bounds = ctx.params().get("extended_bounds");
        if (bounds != null) {
            Map<String, Object> b = ParamsHelper.asMap(bounds);
            boundsMin = b.containsKey("min") ? ((Number) b.get("min")).doubleValue() : null;
            boundsMax = b.containsKey("max") ? ((Number) b.get("max")).doubleValue() : null;
        }
        return new HistogramAggregator(ctx.name(), ctx.subAggregators(), ctx.bucketConsumer(), ctx.lookup().doubleValues(field),
            interval, offset, minDocCount, boundsMin, boundsMax);
    }

    private double bucketKey(double value) {
        return Math.floor((value - offset) / interval) * interval + offset;
    }

    @Override
    public void collect(int doc, long bucketOrd) {
        if (!source.advanceExact(doc)) {
            return;
        }
        int n = source.docValueCount();
        for (int i = 0; i < n; i++) {
            double key = bucketKey(source.nextValue());
            collectBucket(doc, bucketOrd, NumericUtils.doubleToSortableLong(key));
        }
    }

    @Override
    public InternalAggregation buildAggregation(long owningBucketOrd) {
        List<HistogramBucket> buckets = new ArrayList<>();
        var ords = bucketOrds.ordsFor(owningBucketOrd);
        for (int i = 0; i < ords.size(); i++) {
            long bucketOrd = ords.get(i);
            double key = NumericUtils.sortableLongToDouble(bucketOrds.key(bucketOrd));
            InternalAggregations subAggs = buildSubAggsForBucket(bucketOrd);
            buckets.add(new HistogramBucket(key, bucketDocCount(bucketOrd), subAggs));
        }
        buckets.sort((a, b) -> Double.compare((double) a.getKey(), (double) b.getKey()));
        return new InternalHistogram(name, buckets, interval, offset, minDocCount, boundsMin, boundsMax, null);
    }
}
