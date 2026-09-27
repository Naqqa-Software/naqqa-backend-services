package com.naqqa.elasticsearch.search.aggs.bucket.histogram;

import com.naqqa.elasticsearch.search.aggs.AggParseContext;
import com.naqqa.elasticsearch.search.aggs.Aggregator;
import com.naqqa.elasticsearch.search.aggs.BucketsAggregator;
import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.InternalAggregations;
import com.naqqa.elasticsearch.search.aggs.support.BucketArrays;
import com.naqqa.elasticsearch.search.aggs.support.LongValuesSource;
import com.naqqa.elasticsearch.search.aggs.support.MultiBucketConsumer;
import com.naqqa.elasticsearch.search.aggs.support.ParamsHelper;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class AutoDateHistogramAggregator extends BucketsAggregator {

    private static final CalendarInterval[] CANDIDATES = {
        CalendarInterval.SECOND, CalendarInterval.MINUTE, CalendarInterval.HOUR, CalendarInterval.DAY,
        CalendarInterval.WEEK, CalendarInterval.MONTH, CalendarInterval.QUARTER, CalendarInterval.YEAR
    };

    private final LongValuesSource source;
    private final int targetBuckets;
    private final ZoneId zone;
    private long[] owning = new long[16];
    private int[] docs = new int[16];
    private long[] millisValues = new long[16];
    private int bufferedCount = 0;
    private final Set<Long> finalizedOwningOrds = new HashSet<>();
    private final java.util.Map<Long, CalendarInterval> chosenIntervals = new java.util.HashMap<>();

    public AutoDateHistogramAggregator(String name, Aggregator[] subAggregators, MultiBucketConsumer bucketConsumer, LongValuesSource source,
                                        int targetBuckets, ZoneId zone) {
        super(name, subAggregators, bucketConsumer);
        this.source = source;
        this.targetBuckets = targetBuckets;
        this.zone = zone;
    }

    public static Aggregator parse(AggParseContext ctx) {
        String field = ParamsHelper.requireString(ctx.params(), "field");
        int buckets = ParamsHelper.getInt(ctx.params(), "buckets", 10);
        ZoneId zone = ZoneId.of(ParamsHelper.getString(ctx.params(), "time_zone", "UTC"));
        return new AutoDateHistogramAggregator(ctx.name(), ctx.subAggregators(), ctx.bucketConsumer(), ctx.lookup().longValues(field), buckets, zone);
    }

    @Override
    public void collect(int doc, long bucketOrd) {
        if (!source.advanceExact(doc)) {
            return;
        }
        int n = source.docValueCount();
        for (int i = 0; i < n; i++) {
            owning = BucketArrays.grow(owning, bufferedCount + 1);
            docs = BucketArrays.grow(docs, bufferedCount + 1);
            millisValues = BucketArrays.grow(millisValues, bufferedCount + 1);
            owning[bufferedCount] = bucketOrd;
            docs[bufferedCount] = doc;
            millisValues[bufferedCount] = source.nextValue();
            bufferedCount++;
        }
    }

    private CalendarInterval chooseInterval(long[] millisForOrd, int count) {
        for (CalendarInterval candidate : CANDIDATES) {
            Set<Long> keys = new HashSet<>();
            for (int i = 0; i < count; i++) {
                keys.add(candidate.truncateToMillis(millisForOrd[i], zone));
                if (keys.size() > targetBuckets) {
                    break;
                }
            }
            if (keys.size() <= targetBuckets) {
                return candidate;
            }
        }
        return CalendarInterval.YEAR;
    }

    @Override
    public InternalAggregation buildAggregation(long owningBucketOrd) {
        List<Long> matchingMillis = new ArrayList<>();
        List<Integer> matchingDocs = new ArrayList<>();
        for (int i = 0; i < bufferedCount; i++) {
            if (owning[i] == owningBucketOrd) {
                matchingMillis.add(millisValues[i]);
                matchingDocs.add(docs[i]);
            }
        }
        if (!finalizedOwningOrds.contains(owningBucketOrd) && !matchingMillis.isEmpty()) {
            finalizedOwningOrds.add(owningBucketOrd);
            long[] arr = new long[matchingMillis.size()];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = matchingMillis.get(i);
            }
            CalendarInterval chosen = chooseInterval(arr, arr.length);
            chosenIntervals.put(owningBucketOrd, chosen);
            for (int i = 0; i < matchingDocs.size(); i++) {
                long key = chosen.truncateToMillis(matchingMillis.get(i), zone);
                collectBucket(matchingDocs.get(i), owningBucketOrd, key);
            }
        }
        List<DateHistogramBucket> buckets = new ArrayList<>();
        var ords = bucketOrds.ordsFor(owningBucketOrd);
        for (int i = 0; i < ords.size(); i++) {
            long bucketOrd = ords.get(i);
            long key = bucketOrds.key(bucketOrd);
            InternalAggregations subAggs = buildSubAggsForBucket(bucketOrd);
            buckets.add(new DateHistogramBucket(key, bucketDocCount(bucketOrd), subAggs));
        }
        buckets.sort((a, b) -> Long.compare(a.getKey(), b.getKey()));
        CalendarInterval chosenForOutput = chosenIntervals.getOrDefault(owningBucketOrd, CalendarInterval.SECOND);
        return new InternalDateHistogram(name, buckets, chosenForOutput, 0, zone, 0, 0, null, null, null);
    }
}
