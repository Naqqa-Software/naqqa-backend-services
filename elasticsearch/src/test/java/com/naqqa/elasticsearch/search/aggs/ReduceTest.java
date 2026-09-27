package com.naqqa.elasticsearch.search.aggs;

import com.naqqa.elasticsearch.search.aggs.bucket.histogram.HistogramAggregator;
import com.naqqa.elasticsearch.search.aggs.bucket.histogram.HistogramBucket;
import com.naqqa.elasticsearch.search.aggs.bucket.histogram.InternalHistogram;
import com.naqqa.elasticsearch.search.aggs.metrics.AvgAggregator;
import com.naqqa.elasticsearch.search.aggs.metrics.CardinalityAggregator;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalAvg;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalCardinality;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalStats;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalSum;
import com.naqqa.elasticsearch.search.aggs.metrics.StatsAggregator;
import com.naqqa.elasticsearch.search.aggs.metrics.SumAggregator;
import com.naqqa.elasticsearch.search.aggs.support.MultiBucketConsumer;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ReduceTest {

    private static Map<Integer, double[]> valuesMap(double... values) {
        Map<Integer, double[]> m = new HashMap<>();
        for (int i = 0; i < values.length; i++) {
            m.put(i, new double[]{values[i]});
        }
        return m;
    }

    @Test
    public void avgSumStatsReduceMatchesUnsplitRun() {
        double[] all = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        FakeValuesLookup fullLookup = new FakeValuesLookup().putDoubles("f", valuesMap(all));
        AvgAggregator fullAvg = new AvgAggregator("avg", fullLookup.doubleValues("f"));
        SumAggregator fullSum = new SumAggregator("sum", fullLookup.doubleValues("f"));
        StatsAggregator fullStats = new StatsAggregator("stats", fullLookup.doubleValues("f"));
        for (int i = 0; i < all.length; i++) {
            fullAvg.collect(i, 0);
            fullSum.collect(i, 0);
            fullStats.collect(i, 0);
        }
        InternalAvg unsplitAvg = (InternalAvg) fullAvg.buildAggregation(0);
        InternalSum unsplitSum = (InternalSum) fullSum.buildAggregation(0);
        InternalStats unsplitStats = (InternalStats) fullStats.buildAggregation(0);

        double[] shard1Values = {1, 2, 3};
        double[] shard2Values = {4, 5, 6, 7};
        double[] shard3Values = {8, 9, 10};
        InternalAvg avgPart1 = avgOver(shard1Values);
        InternalAvg avgPart2 = avgOver(shard2Values);
        InternalAvg avgPart3 = avgOver(shard3Values);
        InternalAvg mergedAvg = (InternalAvg) avgPart1.reduce(List.of(avgPart1, avgPart2, avgPart3), ReduceContext.forFinalReduction());
        Assert.assertEquals(unsplitAvg.value(), mergedAvg.value(), 1e-9);

        InternalSum sumPart1 = sumOver(shard1Values);
        InternalSum sumPart2 = sumOver(shard2Values);
        InternalSum sumPart3 = sumOver(shard3Values);
        InternalSum mergedSum = (InternalSum) sumPart1.reduce(List.of(sumPart1, sumPart2, sumPart3), ReduceContext.forFinalReduction());
        Assert.assertEquals(unsplitSum.value(), mergedSum.value(), 1e-9);

        InternalStats statsPart1 = statsOver(shard1Values);
        InternalStats statsPart2 = statsOver(shard2Values);
        InternalStats statsPart3 = statsOver(shard3Values);
        InternalStats mergedStats = (InternalStats) statsPart1.reduce(List.of(statsPart1, statsPart2, statsPart3), ReduceContext.forFinalReduction());
        Assert.assertEquals(unsplitStats.count(), mergedStats.count());
        Assert.assertEquals(unsplitStats.sum(), mergedStats.sum(), 1e-9);
        Assert.assertEquals(unsplitStats.min(), mergedStats.min(), 1e-9);
        Assert.assertEquals(unsplitStats.max(), mergedStats.max(), 1e-9);
    }

    @Test
    public void cardinalityReduceMatchesUnsplitRun() {
        int n = 3000;
        Map<Integer, double[]> all = new HashMap<>();
        for (int i = 0; i < n; i++) {
            all.put(i, new double[]{i});
        }
        FakeValuesLookup fullLookup = new FakeValuesLookup().putDoubles("f", all);
        CardinalityAggregator full = new CardinalityAggregator("c", fullLookup.doubleValues("f"), null, 14);
        for (int i = 0; i < n; i++) {
            full.collect(i, 0);
        }
        InternalCardinality unsplit = (InternalCardinality) full.buildAggregation(0);

        Map<Integer, double[]> shard1 = new HashMap<>();
        Map<Integer, double[]> shard2 = new HashMap<>();
        for (int i = 0; i < n; i++) {
            (i % 2 == 0 ? shard1 : shard2).put(i, new double[]{i});
        }
        FakeValuesLookup lookup1 = new FakeValuesLookup().putDoubles("f", shard1);
        FakeValuesLookup lookup2 = new FakeValuesLookup().putDoubles("f", shard2);
        CardinalityAggregator agg1 = new CardinalityAggregator("c", lookup1.doubleValues("f"), null, 14);
        CardinalityAggregator agg2 = new CardinalityAggregator("c", lookup2.doubleValues("f"), null, 14);
        for (Integer doc : shard1.keySet()) {
            agg1.collect(doc, 0);
        }
        for (Integer doc : shard2.keySet()) {
            agg2.collect(doc, 0);
        }
        InternalCardinality part1 = (InternalCardinality) agg1.buildAggregation(0);
        InternalCardinality part2 = (InternalCardinality) agg2.buildAggregation(0);
        InternalCardinality merged = (InternalCardinality) part1.reduce(List.of(part1, part2), ReduceContext.forFinalReduction());
        Assert.assertEquals(unsplit.cardinality(), merged.cardinality());
    }

    @Test
    public void histogramReduceMatchesUnsplitRun() {
        double[] all = {1, 2, 11, 12, 21, 22, 23};
        FakeValuesLookup fullLookup = new FakeValuesLookup().putDoubles("f", valuesMap(all));
        HistogramAggregator full = new HistogramAggregator("h", new Aggregator[0], new MultiBucketConsumer(10000), fullLookup.doubleValues("f"), 10, 0, 1, null, null);
        for (int i = 0; i < all.length; i++) {
            full.collect(i, 0);
        }
        InternalHistogram unsplit = (InternalHistogram) full.buildAggregation(0);

        double[] shard1Values = {1, 2, 11};
        double[] shard2Values = {12, 21, 22, 23};
        InternalHistogram part1 = histogramOver(shard1Values);
        InternalHistogram part2 = histogramOver(shard2Values);
        InternalHistogram merged = (InternalHistogram) part1.reduce(List.of(part1, part2), ReduceContext.forFinalReduction());

        Map<Object, Long> unsplitCounts = new HashMap<>();
        for (Object b : unsplit.getBuckets()) {
            unsplitCounts.put(((HistogramBucket) b).getKey(), ((HistogramBucket) b).getDocCount());
        }
        Map<Object, Long> mergedCounts = new HashMap<>();
        for (Object b : merged.getBuckets()) {
            mergedCounts.put(((HistogramBucket) b).getKey(), ((HistogramBucket) b).getDocCount());
        }
        Assert.assertEquals(unsplitCounts, mergedCounts);
    }

    private static InternalAvg avgOver(double[] values) {
        FakeValuesLookup lookup = new FakeValuesLookup().putDoubles("f", valuesMap(values));
        AvgAggregator agg = new AvgAggregator("avg", lookup.doubleValues("f"));
        for (int i = 0; i < values.length; i++) {
            agg.collect(i, 0);
        }
        return (InternalAvg) agg.buildAggregation(0);
    }

    private static InternalSum sumOver(double[] values) {
        FakeValuesLookup lookup = new FakeValuesLookup().putDoubles("f", valuesMap(values));
        SumAggregator agg = new SumAggregator("sum", lookup.doubleValues("f"));
        for (int i = 0; i < values.length; i++) {
            agg.collect(i, 0);
        }
        return (InternalSum) agg.buildAggregation(0);
    }

    private static InternalStats statsOver(double[] values) {
        FakeValuesLookup lookup = new FakeValuesLookup().putDoubles("f", valuesMap(values));
        StatsAggregator agg = new StatsAggregator("stats", lookup.doubleValues("f"));
        for (int i = 0; i < values.length; i++) {
            agg.collect(i, 0);
        }
        return (InternalStats) agg.buildAggregation(0);
    }

    private static InternalHistogram histogramOver(double[] values) {
        FakeValuesLookup lookup = new FakeValuesLookup().putDoubles("f", valuesMap(values));
        HistogramAggregator agg = new HistogramAggregator("h", new Aggregator[0], new MultiBucketConsumer(10000), lookup.doubleValues("f"), 10, 0, 1, null, null);
        for (int i = 0; i < values.length; i++) {
            agg.collect(i, 0);
        }
        return (InternalHistogram) agg.buildAggregation(0);
    }
}
