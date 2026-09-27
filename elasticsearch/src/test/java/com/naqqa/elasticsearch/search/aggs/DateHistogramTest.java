package com.naqqa.elasticsearch.search.aggs;

import com.naqqa.elasticsearch.search.aggs.bucket.histogram.DateHistogramAggregator;
import com.naqqa.elasticsearch.search.aggs.bucket.histogram.DateHistogramBucket;
import com.naqqa.elasticsearch.search.aggs.bucket.histogram.InternalDateHistogram;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class DateHistogramTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    @Test
    public void calendarDayIntervalStaysAlignedAcrossDstSpringForward() {
        long docA = ZonedDateTime.of(2023, 3, 12, 1, 0, 0, 0, NY).toInstant().toEpochMilli();
        long docB = ZonedDateTime.of(2023, 3, 13, 1, 0, 0, 0, NY).toInstant().toEpochMilli();
        Map<Integer, long[]> docValues = new HashMap<>();
        docValues.put(0, new long[]{docA});
        docValues.put(1, new long[]{docB});
        FakeValuesLookup lookup = new FakeValuesLookup().putLongs("ts", docValues);

        DateHistogramAggregator agg = new DateHistogramAggregator("dh", new Aggregator[0],
            new com.naqqa.elasticsearch.search.aggs.support.MultiBucketConsumer(10000), lookup.longValues("ts"),
            com.naqqa.elasticsearch.search.aggs.bucket.histogram.CalendarInterval.DAY, 0, NY, 0, 0, null, null);
        agg.collect(0, 0);
        agg.collect(1, 0);
        InternalDateHistogram result = (InternalDateHistogram) agg.buildAggregation(0);
        List<?> buckets = result.getBuckets();
        Assert.assertEquals(2, buckets.size());
        long key0 = ((DateHistogramBucket) buckets.get(0)).getKey();
        long key1 = ((DateHistogramBucket) buckets.get(1)).getKey();
        long expectedKey0 = ZonedDateTime.of(2023, 3, 12, 0, 0, 0, 0, NY).toInstant().toEpochMilli();
        long expectedKey1 = ZonedDateTime.of(2023, 3, 13, 0, 0, 0, 0, NY).toInstant().toEpochMilli();
        Assert.assertEquals(expectedKey0, key0);
        Assert.assertEquals(expectedKey1, key1);
        long gapMillis = key1 - key0;
        Assert.assertEquals(23L * 3600_000L, gapMillis, "calendar day across spring-forward should be 23 hours, not 24");
    }

    @Test
    public void fixedIntervalDoesNotShiftForDst() {
        long oneDayMillis = 86_400_000L;
        long docA = ZonedDateTime.of(2023, 3, 12, 1, 0, 0, 0, NY).toInstant().toEpochMilli();
        long docB = ZonedDateTime.of(2023, 3, 13, 1, 0, 0, 0, NY).toInstant().toEpochMilli();
        Map<Integer, long[]> docValues = new HashMap<>();
        docValues.put(0, new long[]{docA});
        docValues.put(1, new long[]{docB});
        FakeValuesLookup lookup = new FakeValuesLookup().putLongs("ts", docValues);

        DateHistogramAggregator agg = new DateHistogramAggregator("dh", new Aggregator[0],
            new com.naqqa.elasticsearch.search.aggs.support.MultiBucketConsumer(10000), lookup.longValues("ts"),
            null, oneDayMillis, NY, 0, 0, null, null);
        agg.collect(0, 0);
        agg.collect(1, 0);
        InternalDateHistogram result = (InternalDateHistogram) agg.buildAggregation(0);
        List<?> buckets = result.getBuckets();
        long key0 = ((DateHistogramBucket) buckets.get(0)).getKey();
        long key1 = ((DateHistogramBucket) buckets.get(buckets.size() - 1)).getKey();
        long gap = key1 - key0;
        Assert.assertEquals(0L, gap % oneDayMillis, "fixed interval buckets must stay an exact multiple of the interval apart, unlike calendar days");
        Assert.assertTrue(gap == 0 || gap == oneDayMillis, "docs 24h apart in wall-clock but 23h apart in real time should land in the same or adjacent fixed bucket: gap=" + gap);
    }

    @Test
    public void reduceMergesDateHistogramShardsCorrectly() {
        long day1 = ZonedDateTime.of(2023, 1, 1, 0, 0, 0, 0, NY).toInstant().toEpochMilli();
        long day2 = ZonedDateTime.of(2023, 1, 2, 0, 0, 0, 0, NY).toInstant().toEpochMilli();
        Map<Integer, long[]> shard1Docs = new HashMap<>();
        shard1Docs.put(0, new long[]{day1 + 3600_000});
        shard1Docs.put(1, new long[]{day1 + 7200_000});
        Map<Integer, long[]> shard2Docs = new HashMap<>();
        shard2Docs.put(0, new long[]{day2 + 1000});
        FakeValuesLookup lookup1 = new FakeValuesLookup().putLongs("ts", shard1Docs);
        FakeValuesLookup lookup2 = new FakeValuesLookup().putLongs("ts", shard2Docs);

        DateHistogramAggregator shard1 = new DateHistogramAggregator("dh", new Aggregator[0],
            new com.naqqa.elasticsearch.search.aggs.support.MultiBucketConsumer(10000), lookup1.longValues("ts"),
            com.naqqa.elasticsearch.search.aggs.bucket.histogram.CalendarInterval.DAY, 0, NY, 0, 0, null, null);
        shard1.collect(0, 0);
        shard1.collect(1, 0);
        DateHistogramAggregator shard2 = new DateHistogramAggregator("dh", new Aggregator[0],
            new com.naqqa.elasticsearch.search.aggs.support.MultiBucketConsumer(10000), lookup2.longValues("ts"),
            com.naqqa.elasticsearch.search.aggs.bucket.histogram.CalendarInterval.DAY, 0, NY, 0, 0, null, null);
        shard2.collect(0, 0);

        InternalDateHistogram part1 = (InternalDateHistogram) shard1.buildAggregation(0);
        InternalDateHistogram part2 = (InternalDateHistogram) shard2.buildAggregation(0);
        InternalDateHistogram merged = (InternalDateHistogram) part1.reduce(List.of(part1, part2), ReduceContext.forFinalReduction());
        Assert.assertEquals(2, merged.getBuckets().size());
        Assert.assertEquals(2L, ((DateHistogramBucket) merged.getBuckets().get(0)).getDocCount());
        Assert.assertEquals(1L, ((DateHistogramBucket) merged.getBuckets().get(1)).getDocCount());
    }
}
