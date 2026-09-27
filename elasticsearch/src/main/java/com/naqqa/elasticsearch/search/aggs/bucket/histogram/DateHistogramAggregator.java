package com.naqqa.elasticsearch.search.aggs.bucket.histogram;

import com.naqqa.elasticsearch.search.aggs.AggParseContext;
import com.naqqa.elasticsearch.search.aggs.Aggregator;
import com.naqqa.elasticsearch.search.aggs.BucketsAggregator;
import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.InternalAggregations;
import com.naqqa.elasticsearch.search.aggs.support.LongValuesSource;
import com.naqqa.elasticsearch.search.aggs.support.MultiBucketConsumer;
import com.naqqa.elasticsearch.search.aggs.support.ParamsHelper;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class DateHistogramAggregator extends BucketsAggregator {

    private final LongValuesSource source;
    private final CalendarInterval calendarInterval;
    private final long fixedIntervalMillis;
    private final ZoneId zone;
    private final long offsetMillis;
    private final long minDocCount;
    private final Long boundsMin;
    private final Long boundsMax;

    public DateHistogramAggregator(String name, Aggregator[] subAggregators, MultiBucketConsumer bucketConsumer, LongValuesSource source,
                                    CalendarInterval calendarInterval, long fixedIntervalMillis, ZoneId zone, long offsetMillis,
                                    long minDocCount, Long boundsMin, Long boundsMax) {
        super(name, subAggregators, bucketConsumer);
        this.source = source;
        this.calendarInterval = calendarInterval;
        this.fixedIntervalMillis = fixedIntervalMillis;
        this.zone = zone;
        this.offsetMillis = offsetMillis;
        this.minDocCount = minDocCount;
        this.boundsMin = boundsMin;
        this.boundsMax = boundsMax;
    }

    public static long parseFixedInterval(Object spec) {
        if (spec instanceof Number n) {
            return n.longValue();
        }
        String s = String.valueOf(spec).trim();
        int i = 0;
        while (i < s.length() && (Character.isDigit(s.charAt(i)))) {
            i++;
        }
        long value = Long.parseLong(s.substring(0, i));
        String unit = s.substring(i);
        long unitMillis = switch (unit) {
            case "ms" -> 1L;
            case "s" -> 1000L;
            case "m" -> 60_000L;
            case "h" -> 3_600_000L;
            case "d" -> 86_400_000L;
            default -> throw new IllegalArgumentException("unknown fixed_interval unit [" + unit + "]");
        };
        return value * unitMillis;
    }

    public static Aggregator parse(AggParseContext ctx) {
        String field = ParamsHelper.requireString(ctx.params(), "field");
        ZoneId zone = ZoneId.of(ParamsHelper.getString(ctx.params(), "time_zone", "UTC"));
        long offsetMillis = ctx.params().containsKey("offset") ? parseFixedInterval(ctx.params().get("offset")) : 0L;
        long minDocCount = ParamsHelper.getLong(ctx.params(), "min_doc_count", 0);
        CalendarInterval calendarInterval = null;
        long fixedIntervalMillis = 0;
        if (ctx.params().containsKey("calendar_interval")) {
            calendarInterval = CalendarInterval.fromString(String.valueOf(ctx.params().get("calendar_interval")));
        } else if (ctx.params().containsKey("fixed_interval")) {
            fixedIntervalMillis = parseFixedInterval(ctx.params().get("fixed_interval"));
        } else {
            throw new IllegalArgumentException("date_histogram requires either calendar_interval or fixed_interval");
        }
        Long boundsMin = null;
        Long boundsMax = null;
        Object bounds = ctx.params().get("extended_bounds");
        if (bounds != null) {
            Map<String, Object> b = ParamsHelper.asMap(bounds);
            boundsMin = b.containsKey("min") ? ((Number) b.get("min")).longValue() : null;
            boundsMax = b.containsKey("max") ? ((Number) b.get("max")).longValue() : null;
        }
        return new DateHistogramAggregator(ctx.name(), ctx.subAggregators(), ctx.bucketConsumer(), ctx.lookup().longValues(field),
            calendarInterval, fixedIntervalMillis, zone, offsetMillis, minDocCount, boundsMin, boundsMax);
    }

    private long bucketKey(long millis) {
        long shifted = millis - offsetMillis;
        long rounded = calendarInterval != null ? calendarInterval.truncateToMillis(shifted, zone) : Math.floorDiv(shifted, fixedIntervalMillis) * fixedIntervalMillis;
        return rounded + offsetMillis;
    }

    @Override
    public void collect(int doc, long bucketOrd) {
        if (!source.advanceExact(doc)) {
            return;
        }
        int n = source.docValueCount();
        for (int i = 0; i < n; i++) {
            long key = bucketKey(source.nextValue());
            collectBucket(doc, bucketOrd, key);
        }
    }

    @Override
    public InternalAggregation buildAggregation(long owningBucketOrd) {
        List<DateHistogramBucket> buckets = new ArrayList<>();
        var ords = bucketOrds.ordsFor(owningBucketOrd);
        for (int i = 0; i < ords.size(); i++) {
            long bucketOrd = ords.get(i);
            long key = bucketOrds.key(bucketOrd);
            InternalAggregations subAggs = buildSubAggsForBucket(bucketOrd);
            buckets.add(new DateHistogramBucket(key, bucketDocCount(bucketOrd), subAggs));
        }
        buckets.sort((a, b) -> Long.compare(a.getKey(), b.getKey()));
        return new InternalDateHistogram(name, buckets, calendarInterval, fixedIntervalMillis, zone, offsetMillis, minDocCount, boundsMin, boundsMax, null);
    }
}
