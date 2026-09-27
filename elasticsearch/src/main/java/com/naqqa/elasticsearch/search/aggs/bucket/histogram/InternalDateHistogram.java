package com.naqqa.elasticsearch.search.aggs.bucket.histogram;

import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.InternalAggregations;
import com.naqqa.elasticsearch.search.aggs.MultiBucketsAggregation;
import com.naqqa.elasticsearch.search.aggs.ReduceContext;
import com.naqqa.elasticsearch.search.aggs.bucket.BucketReduceUtil;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InternalDateHistogram extends InternalAggregation implements MultiBucketsAggregation {

    private final List<DateHistogramBucket> buckets;
    private final CalendarInterval calendarInterval;
    private final long fixedIntervalMillis;
    private final ZoneId zone;
    private final long offsetMillis;
    private final long minDocCount;
    private final Long boundsMin;
    private final Long boundsMax;

    public InternalDateHistogram(String name, List<DateHistogramBucket> buckets, CalendarInterval calendarInterval, long fixedIntervalMillis,
                                  ZoneId zone, long offsetMillis, long minDocCount, Long boundsMin, Long boundsMax, Map<String, Object> metadata) {
        super(name, metadata);
        this.buckets = buckets;
        this.calendarInterval = calendarInterval;
        this.fixedIntervalMillis = fixedIntervalMillis;
        this.zone = zone;
        this.offsetMillis = offsetMillis;
        this.minDocCount = minDocCount;
        this.boundsMin = boundsMin;
        this.boundsMax = boundsMax;
    }

    @Override
    public List<? extends Bucket> getBuckets() {
        return buckets;
    }

    public CalendarInterval getCalendarInterval() {
        return calendarInterval;
    }

    public long getFixedIntervalMillis() {
        return fixedIntervalMillis;
    }

    public ZoneId getZone() {
        return zone;
    }

    public long getOffsetMillis() {
        return offsetMillis;
    }

    public long getMinDocCount() {
        return minDocCount;
    }

    public Long getBoundsMin() {
        return boundsMin;
    }

    public Long getBoundsMax() {
        return boundsMax;
    }

    @Override
    public InternalAggregation withBuckets(List<? extends Bucket> newBuckets) {
        List<DateHistogramBucket> cast = new ArrayList<>(newBuckets.size());
        for (Bucket b : newBuckets) {
            cast.add((DateHistogramBucket) b);
        }
        return new InternalDateHistogram(getName(), cast, calendarInterval, fixedIntervalMillis, zone, offsetMillis, minDocCount, boundsMin, boundsMax, getMetadata());
    }

    @Override
    public String getType() {
        return "date_histogram";
    }

    private long nextKey(long key) {
        if (calendarInterval != null) {
            return calendarInterval.nextBucketMillis(key - offsetMillis, zone) + offsetMillis;
        }
        return key + fixedIntervalMillis;
    }

    @Override
    public InternalAggregation reduce(List<InternalAggregation> aggregations, ReduceContext context) {
        List<List<DateHistogramBucket>> perShard = new ArrayList<>();
        for (InternalAggregation a : aggregations) {
            perShard.add(((InternalDateHistogram) a).buckets);
        }
        LinkedHashMap<Object, List<DateHistogramBucket>> grouped = BucketReduceUtil.groupByKey(perShard);
        List<DateHistogramBucket> reduced = new ArrayList<>();
        for (List<DateHistogramBucket> members : grouped.values()) {
            long docCount = BucketReduceUtil.sumDocCount(members);
            InternalAggregations subAggs = BucketReduceUtil.reduceSubAggs(members, context);
            reduced.add(new DateHistogramBucket(members.get(0).getKey(), docCount, subAggs));
        }
        reduced.sort((a, b) -> Long.compare(a.getKey(), b.getKey()));
        if (context.isFinalReduce()) {
            reduced = applyMinDocCountAndBounds(reduced, context);
        }
        return new InternalDateHistogram(getName(), reduced, calendarInterval, fixedIntervalMillis, zone, offsetMillis, minDocCount, boundsMin, boundsMax, getMetadata());
    }

    private List<DateHistogramBucket> applyMinDocCountAndBounds(List<DateHistogramBucket> sorted, ReduceContext context) {
        if (minDocCount > 0 && boundsMin == null) {
            List<DateHistogramBucket> filtered = new ArrayList<>();
            for (DateHistogramBucket b : sorted) {
                if (b.getDocCount() >= minDocCount) {
                    filtered.add(b);
                }
            }
            context.consumeBucketsAndMaybeBreak(filtered.size());
            return filtered;
        }
        if (sorted.isEmpty() && boundsMin == null) {
            return sorted;
        }
        long lo = sorted.isEmpty() ? boundsMin : sorted.get(0).getKey();
        long hi = sorted.isEmpty() ? boundsMax : sorted.get(sorted.size() - 1).getKey();
        if (boundsMin != null) {
            lo = Math.min(lo, boundsMin);
        }
        if (boundsMax != null) {
            hi = Math.max(hi, boundsMax);
        }
        Map<Long, DateHistogramBucket> byKey = new HashMap<>();
        for (DateHistogramBucket b : sorted) {
            byKey.put(b.getKey(), b);
        }
        List<DateHistogramBucket> filled = new ArrayList<>();
        long k = lo;
        int guard = 0;
        while (k <= hi && guard < 1_000_000) {
            DateHistogramBucket existing = byKey.get(k);
            if (existing != null) {
                if (existing.getDocCount() >= minDocCount) {
                    filled.add(existing);
                }
            } else if (minDocCount <= 0) {
                filled.add(new DateHistogramBucket(k, 0, InternalAggregations.EMPTY));
            }
            long next = nextKey(k);
            if (next <= k) {
                break;
            }
            k = next;
            guard++;
        }
        context.consumeBucketsAndMaybeBreak(filled.size());
        return filled;
    }

    @Override
    public Map<String, Object> toMap() {
        List<Object> list = new ArrayList<>();
        for (DateHistogramBucket b : buckets) {
            list.add(b.toMap(zone));
        }
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("buckets", list);
        return map;
    }
}
