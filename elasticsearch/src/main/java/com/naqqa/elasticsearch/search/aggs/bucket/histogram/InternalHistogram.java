package com.naqqa.elasticsearch.search.aggs.bucket.histogram;

import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.InternalAggregations;
import com.naqqa.elasticsearch.search.aggs.MultiBucketsAggregation;
import com.naqqa.elasticsearch.search.aggs.ReduceContext;
import com.naqqa.elasticsearch.search.aggs.bucket.BucketReduceUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InternalHistogram extends InternalAggregation implements MultiBucketsAggregation {

    private final List<HistogramBucket> buckets;
    private final double interval;
    private final double offset;
    private final long minDocCount;
    private final Double boundsMin;
    private final Double boundsMax;

    public InternalHistogram(String name, List<HistogramBucket> buckets, double interval, double offset, long minDocCount,
                              Double boundsMin, Double boundsMax, Map<String, Object> metadata) {
        super(name, metadata);
        this.buckets = buckets;
        this.interval = interval;
        this.offset = offset;
        this.minDocCount = minDocCount;
        this.boundsMin = boundsMin;
        this.boundsMax = boundsMax;
    }

    @Override
    public List<? extends Bucket> getBuckets() {
        return buckets;
    }

    @Override
    public InternalAggregation withBuckets(List<? extends Bucket> newBuckets) {
        List<HistogramBucket> cast = new ArrayList<>(newBuckets.size());
        for (Bucket b : newBuckets) {
            cast.add((HistogramBucket) b);
        }
        return new InternalHistogram(getName(), cast, interval, offset, minDocCount, boundsMin, boundsMax, getMetadata());
    }

    @Override
    public String getType() {
        return "histogram";
    }

    @Override
    public InternalAggregation reduce(List<InternalAggregation> aggregations, ReduceContext context) {
        List<List<HistogramBucket>> perShard = new ArrayList<>();
        for (InternalAggregation a : aggregations) {
            perShard.add(((InternalHistogram) a).buckets);
        }
        LinkedHashMap<Object, List<HistogramBucket>> grouped = BucketReduceUtil.groupByKey(perShard);
        List<HistogramBucket> reduced = new ArrayList<>();
        for (List<HistogramBucket> members : grouped.values()) {
            long docCount = BucketReduceUtil.sumDocCount(members);
            InternalAggregations subAggs = BucketReduceUtil.reduceSubAggs(members, context);
            reduced.add(new HistogramBucket((double) members.get(0).getKey(), docCount, subAggs));
        }
        reduced.sort((a, b) -> Double.compare((double) a.getKey(), (double) b.getKey()));
        if (context.isFinalReduce()) {
            reduced = applyMinDocCountAndBounds(reduced, context);
        }
        return new InternalHistogram(getName(), reduced, interval, offset, minDocCount, boundsMin, boundsMax, getMetadata());
    }

    private List<HistogramBucket> applyMinDocCountAndBounds(List<HistogramBucket> sorted, ReduceContext context) {
        if (minDocCount > 0 && boundsMin == null) {
            List<HistogramBucket> filtered = new ArrayList<>();
            for (HistogramBucket b : sorted) {
                if (b.getDocCount() >= minDocCount) {
                    filtered.add(b);
                }
            }
            context.consumeBucketsAndMaybeBreak(filtered.size());
            return filtered;
        }
        double lo;
        double hi;
        if (sorted.isEmpty() && boundsMin == null) {
            return sorted;
        }
        lo = sorted.isEmpty() ? boundsMin : (double) sorted.get(0).getKey();
        hi = sorted.isEmpty() ? boundsMax : (double) sorted.get(sorted.size() - 1).getKey();
        if (boundsMin != null) {
            lo = Math.min(lo, roundKey(boundsMin));
        }
        if (boundsMax != null) {
            hi = Math.max(hi, roundKey(boundsMax));
        }
        Map<Double, HistogramBucket> byKey = new java.util.HashMap<>();
        for (HistogramBucket b : sorted) {
            byKey.put((double) b.getKey(), b);
        }
        List<HistogramBucket> filled = new ArrayList<>();
        int guard = 0;
        for (double k = lo; k <= hi + interval / 2 && guard < 1_000_000; k += interval, guard++) {
            HistogramBucket existing = byKey.get(k);
            if (existing != null) {
                if (existing.getDocCount() >= minDocCount) {
                    filled.add(existing);
                }
            } else if (minDocCount <= 0) {
                filled.add(new HistogramBucket(k, 0, InternalAggregations.EMPTY));
            }
        }
        context.consumeBucketsAndMaybeBreak(filled.size());
        return filled;
    }

    private double roundKey(double value) {
        return Math.floor((value - offset) / interval) * interval + offset;
    }

    @Override
    public Map<String, Object> toMap() {
        List<Object> list = new ArrayList<>();
        for (HistogramBucket b : buckets) {
            list.add(b.toMap());
        }
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("buckets", list);
        return map;
    }
}
