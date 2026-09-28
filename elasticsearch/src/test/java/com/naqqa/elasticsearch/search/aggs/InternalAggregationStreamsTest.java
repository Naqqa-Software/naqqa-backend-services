package com.naqqa.elasticsearch.search.aggs;

import com.naqqa.elasticsearch.common.io.stream.BytesStreamOutput;
import com.naqqa.elasticsearch.common.io.stream.ByteBufferStreamInput;
import com.naqqa.elasticsearch.search.aggs.bucket.composite.CompositeBucket;
import com.naqqa.elasticsearch.search.aggs.bucket.composite.InternalComposite;
import com.naqqa.elasticsearch.search.aggs.bucket.filter.FiltersBucket;
import com.naqqa.elasticsearch.search.aggs.bucket.filter.InternalFilters;
import com.naqqa.elasticsearch.search.aggs.bucket.filter.InternalSingleBucketAggregation;
import com.naqqa.elasticsearch.search.aggs.bucket.histogram.DateHistogramBucket;
import com.naqqa.elasticsearch.search.aggs.bucket.histogram.HistogramBucket;
import com.naqqa.elasticsearch.search.aggs.bucket.histogram.InternalDateHistogram;
import com.naqqa.elasticsearch.search.aggs.bucket.histogram.InternalHistogram;
import com.naqqa.elasticsearch.search.aggs.bucket.histogram.InternalVariableWidthHistogram;
import com.naqqa.elasticsearch.search.aggs.bucket.histogram.VariableWidthHistogramBucket;
import com.naqqa.elasticsearch.search.aggs.bucket.range.InternalIpRange;
import com.naqqa.elasticsearch.search.aggs.bucket.range.InternalRange;
import com.naqqa.elasticsearch.search.aggs.bucket.range.IpRangeBucket;
import com.naqqa.elasticsearch.search.aggs.bucket.range.IpRangeSpec;
import com.naqqa.elasticsearch.search.aggs.bucket.range.RangeBucket;
import com.naqqa.elasticsearch.search.aggs.bucket.range.RangeSpec;
import com.naqqa.elasticsearch.search.aggs.bucket.terms.InternalRareTerms;
import com.naqqa.elasticsearch.search.aggs.bucket.terms.InternalSignificantTerms;
import com.naqqa.elasticsearch.search.aggs.bucket.terms.InternalTerms;
import com.naqqa.elasticsearch.search.aggs.bucket.terms.SignificanceHeuristic;
import com.naqqa.elasticsearch.search.aggs.bucket.terms.SignificantTermsBucket;
import com.naqqa.elasticsearch.search.aggs.bucket.terms.TermsBucket;
import com.naqqa.elasticsearch.search.aggs.bucket.terms.TermsOrder;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalAvg;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalBoxplot;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalCardinality;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalExtendedStats;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalGeoBounds;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalGeoCentroid;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalMatrixStats;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalMax;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalMedianAbsoluteDeviation;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalMin;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalPercentileRanks;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalPercentiles;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalRate;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalScriptedMetric;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalStats;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalStringStats;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalSum;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalTTest;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalTopHits;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalTopMetrics;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalValueCount;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalWeightedAvg;
import com.naqqa.elasticsearch.search.aggs.metrics.MatrixStatsState;
import com.naqqa.elasticsearch.search.aggs.metrics.PercentilesMethod;
import com.naqqa.elasticsearch.search.aggs.support.MultiBucketConsumer;
import com.naqqa.elasticsearch.search.aggs.support.sketch.DoubleHdrHistogram;
import com.naqqa.elasticsearch.search.aggs.support.sketch.HyperLogLogPlusPlus;
import com.naqqa.elasticsearch.search.aggs.support.sketch.TDigest;
import com.naqqa.elasticsearch.search.aggs.support.sketch.TTest;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.io.IOException;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InternalAggregationStreamsTest {

    private static InternalAggregation roundTrip(InternalAggregation agg) throws IOException {
        BytesStreamOutput out = new BytesStreamOutput();
        InternalAggregationStreams.writeAggregation(out, agg);
        ByteBufferStreamInput in = new ByteBufferStreamInput(out.toByteArray());
        return InternalAggregationStreams.readAggregation(in);
    }

    private static ReduceContext ctx() {
        return ReduceContext.forFinalReduction(new MultiBucketConsumer(MultiBucketConsumer.DEFAULT_MAX_BUCKETS));
    }

    private static void checkRoundTripReduce(InternalAggregation partA, InternalAggregation partB) throws IOException {
        InternalAggregation rtA = roundTrip(partA);
        InternalAggregation rtB = roundTrip(partB);
        Assert.assertEquals(partA.getType(), rtA.getType());
        Assert.assertEquals(partA.getName(), rtA.getName());
        InternalAggregation originalReduced = partA.reduce(List.of(partA, partB), ctx());
        InternalAggregation roundTrippedReduced = rtA.reduce(List.of(rtA, rtB), ctx());
        Assert.assertEquals(originalReduced.toMap(), roundTrippedReduced.toMap(),
            "mismatch for type [" + partA.getType() + "]");
    }

    @Test
    public void avgRoundTrips() throws IOException {
        checkRoundTripReduce(new InternalAvg("a", 10.0, 2, null), new InternalAvg("a", 20.0, 3, null));
    }

    @Test
    public void sumRoundTrips() throws IOException {
        checkRoundTripReduce(new InternalSum("a", 10.0, null), new InternalSum("a", 20.0, null));
    }

    @Test
    public void minRoundTrips() throws IOException {
        checkRoundTripReduce(new InternalMin("a", 1.0, null), new InternalMin("a", -5.0, null));
    }

    @Test
    public void maxRoundTrips() throws IOException {
        checkRoundTripReduce(new InternalMax("a", 1.0, null), new InternalMax("a", 5.0, null));
    }

    @Test
    public void valueCountRoundTrips() throws IOException {
        checkRoundTripReduce(new InternalValueCount("a", 3, null), new InternalValueCount("a", 4, null));
    }

    @Test
    public void cardinalityRoundTrips() throws IOException {
        HyperLogLogPlusPlus a = new HyperLogLogPlusPlus(14, 1);
        for (int i = 0; i < 500; i++) {
            a.collectLong(0, i);
        }
        HyperLogLogPlusPlus b = new HyperLogLogPlusPlus(14, 1);
        for (int i = 300; i < 900; i++) {
            b.collectLong(0, i);
        }
        checkRoundTripReduce(new InternalCardinality("c", a, null), new InternalCardinality("c", b, null));
    }

    @Test
    public void percentilesTDigestRoundTrips() throws IOException {
        TDigest da = new TDigest(100.0);
        for (int i = 0; i < 200; i++) {
            da.add(i);
        }
        TDigest db = new TDigest(100.0);
        for (int i = 200; i < 400; i++) {
            db.add(i);
        }
        checkRoundTripReduce(
            new InternalPercentiles("p", PercentilesMethod.TDIGEST, new double[] {50, 99}, da, null, null),
            new InternalPercentiles("p", PercentilesMethod.TDIGEST, new double[] {50, 99}, db, null, null));
    }

    @Test
    public void percentilesHdrRoundTrips() throws IOException {
        DoubleHdrHistogram ha = new DoubleHdrHistogram(3);
        for (int i = 1; i <= 100; i++) {
            ha.recordValue(i);
        }
        DoubleHdrHistogram hb = new DoubleHdrHistogram(3);
        for (int i = 101; i <= 200; i++) {
            hb.recordValue(i);
        }
        checkRoundTripReduce(
            new InternalPercentiles("p", PercentilesMethod.HDR, new double[] {50, 99}, null, ha, null),
            new InternalPercentiles("p", PercentilesMethod.HDR, new double[] {50, 99}, null, hb, null));
    }

    @Test
    public void percentileRanksTDigestRoundTrips() throws IOException {
        TDigest da = new TDigest(100.0);
        for (int i = 0; i < 200; i++) {
            da.add(i);
        }
        TDigest db = new TDigest(100.0);
        for (int i = 200; i < 400; i++) {
            db.add(i);
        }
        checkRoundTripReduce(
            new InternalPercentileRanks("pr", PercentilesMethod.TDIGEST, new double[] {100, 300}, da, null, null),
            new InternalPercentileRanks("pr", PercentilesMethod.TDIGEST, new double[] {100, 300}, db, null, null));
    }

    @Test
    public void statsRoundTrips() throws IOException {
        checkRoundTripReduce(new InternalStats("s", 3, 30.0, 5.0, 15.0, null), new InternalStats("s", 4, 40.0, 1.0, 20.0, null));
    }

    @Test
    public void extendedStatsRoundTrips() throws IOException {
        checkRoundTripReduce(new InternalExtendedStats("es", 3, 30.0, 5.0, 15.0, 400.0, 2.0, null),
            new InternalExtendedStats("es", 4, 40.0, 1.0, 20.0, 500.0, 2.0, null));
    }

    @Test
    public void topHitsRoundTrips() throws IOException {
        Map<String, Object> src1 = new LinkedHashMap<>();
        src1.put("f", "v1");
        Map<String, Object> src2 = new LinkedHashMap<>();
        src2.put("f", "v2");
        checkRoundTripReduce(
            new InternalTopHits("th", 2, false, List.of(new InternalTopHits.Hit(3.0, src1)), null),
            new InternalTopHits("th", 2, false, List.of(new InternalTopHits.Hit(9.0, src2)), null));
    }

    @Test
    public void topMetricsRoundTrips() throws IOException {
        Map<String, Double> m1 = new LinkedHashMap<>();
        m1.put("m", 1.0);
        Map<String, Double> m2 = new LinkedHashMap<>();
        m2.put("m", 2.0);
        checkRoundTripReduce(
            new InternalTopMetrics("tm", 2, false, List.of(new InternalTopMetrics.TopMetric(3.0, m1)), null),
            new InternalTopMetrics("tm", 2, false, List.of(new InternalTopMetrics.TopMetric(9.0, m2)), null));
    }

    @Test
    public void geoBoundsRoundTrips() throws IOException {
        checkRoundTripReduce(new InternalGeoBounds("gb", 10, -10, -20, 20, null), new InternalGeoBounds("gb", 30, 0, -5, 40, null));
    }

    @Test
    public void geoCentroidRoundTrips() throws IOException {
        checkRoundTripReduce(new InternalGeoCentroid("gc", 10.0, 20.0, 2, null), new InternalGeoCentroid("gc", 5.0, 15.0, 1, null));
    }

    @Test
    public void scriptedMetricRoundTrips() throws IOException {
        checkRoundTripReduce(new InternalScriptedMetric("sm", List.of(1, 2), null, null),
            new InternalScriptedMetric("sm", List.of(3, 4), null, null));
    }

    @Test
    public void stringStatsRoundTrips() throws IOException {
        long[] freqA = new long[256];
        freqA['a'] = 3;
        long[] freqB = new long[256];
        freqB['b'] = 4;
        checkRoundTripReduce(new InternalStringStats("ss", 3, 2, 5, 12.0, freqA, true, null),
            new InternalStringStats("ss", 4, 1, 6, 16.0, freqB, true, null));
    }

    @Test
    public void boxplotRoundTrips() throws IOException {
        TDigest da = new TDigest(100.0);
        for (int i = 0; i < 100; i++) {
            da.add(i);
        }
        TDigest db = new TDigest(100.0);
        for (int i = 100; i < 200; i++) {
            db.add(i);
        }
        checkRoundTripReduce(new InternalBoxplot("bp", da, null), new InternalBoxplot("bp", db, null));
    }

    @Test
    public void rateRoundTrips() throws IOException {
        checkRoundTripReduce(new InternalRate("r", 10.0, 2.0, null), new InternalRate("r", 20.0, 2.0, null));
    }

    @Test
    public void tTestRoundTrips() throws IOException {
        TTest ta = new TTest(TTest.Type.HOMOSCEDASTIC, 2);
        ta.addA(1.0);
        ta.addA(2.0);
        ta.addB(3.0);
        ta.addB(4.0);
        TTest tb = new TTest(TTest.Type.HOMOSCEDASTIC, 2);
        tb.addA(5.0);
        tb.addB(6.0);
        checkRoundTripReduce(new InternalTTest("tt", ta, null), new InternalTTest("tt", tb, null));
    }

    @Test
    public void matrixStatsRoundTrips() throws IOException {
        MatrixStatsState sa = new MatrixStatsState(new String[] {"x", "y"});
        sa.add(new double[] {1, 2});
        sa.add(new double[] {2, 4});
        MatrixStatsState sb = new MatrixStatsState(new String[] {"x", "y"});
        sb.add(new double[] {3, 6});
        checkRoundTripReduce(new InternalMatrixStats("ms", sa, null), new InternalMatrixStats("ms", sb, null));
    }

    @Test
    public void weightedAvgRoundTrips() throws IOException {
        checkRoundTripReduce(new InternalWeightedAvg("wa", 30.0, 3.0, null), new InternalWeightedAvg("wa", 20.0, 2.0, null));
    }

    @Test
    public void medianAbsoluteDeviationRoundTrips() throws IOException {
        TDigest da = new TDigest(100.0);
        for (int i = 0; i < 50; i++) {
            da.add(i);
        }
        TDigest db = new TDigest(100.0);
        for (int i = 50; i < 100; i++) {
            db.add(i);
        }
        checkRoundTripReduce(new InternalMedianAbsoluteDeviation("mad", da, null), new InternalMedianAbsoluteDeviation("mad", db, null));
    }

    @Test
    public void histogramRoundTrips() throws IOException {
        List<HistogramBucket> ba = List.of(new HistogramBucket(0.0, 2, InternalAggregations.EMPTY));
        List<HistogramBucket> bb = List.of(new HistogramBucket(0.0, 3, InternalAggregations.EMPTY),
            new HistogramBucket(10.0, 1, InternalAggregations.EMPTY));
        checkRoundTripReduce(new InternalHistogram("h", ba, 10, 0, 1, 5.0, 15.0, null),
            new InternalHistogram("h", bb, 10, 0, 1, 5.0, 15.0, null));
    }

    @Test
    public void variableWidthHistogramRoundTrips() throws IOException {
        List<VariableWidthHistogramBucket> ba = List.of(new VariableWidthHistogramBucket(2.0, 1.0, 3.0, 2, InternalAggregations.EMPTY));
        List<VariableWidthHistogramBucket> bb = List.of(new VariableWidthHistogramBucket(12.0, 10.0, 14.0, 3, InternalAggregations.EMPTY));
        checkRoundTripReduce(new InternalVariableWidthHistogram("vwh", ba, 5, null),
            new InternalVariableWidthHistogram("vwh", bb, 5, null));
    }

    @Test
    public void rangeRoundTrips() throws IOException {
        List<RangeBucket> ba = List.of(new RangeBucket(new RangeSpec("low", null, 10.0), 2, InternalAggregations.EMPTY));
        List<RangeBucket> bb = List.of(new RangeBucket(new RangeSpec("low", null, 10.0), 5, InternalAggregations.EMPTY));
        checkRoundTripReduce(new InternalRange("range", ba, null), new InternalRange("range", bb, null));
    }

    @Test
    public void ipRangeRoundTrips() throws IOException {
        List<IpRangeBucket> ba = List.of(new IpRangeBucket(new IpRangeSpec("cidr1", "10.0.0.0/8", null, null), 2, InternalAggregations.EMPTY));
        List<IpRangeBucket> bb = List.of(new IpRangeBucket(new IpRangeSpec("cidr1", "10.0.0.0/8", null, null), 6, InternalAggregations.EMPTY));
        checkRoundTripReduce(new InternalIpRange("ipr", ba, null), new InternalIpRange("ipr", bb, null));
    }

    @Test
    public void filtersRoundTrips() throws IOException {
        List<FiltersBucket> ba = List.of(new FiltersBucket("f1", 2, InternalAggregations.EMPTY));
        List<FiltersBucket> bb = List.of(new FiltersBucket("f1", 5, InternalAggregations.EMPTY));
        checkRoundTripReduce(new InternalFilters("filters", ba, true, null), new InternalFilters("filters", bb, true, null));
    }

    @Test
    public void singleBucketTypesRoundTrip() throws IOException {
        for (String type : List.of("filter", "global", "missing", "nested", "reverse_nested",
            "children", "parent", "sampler", "diversified_sampler", "random_sampler")) {
            checkRoundTripReduce(new InternalSingleBucketAggregation("sb", type, 2, InternalAggregations.EMPTY, null),
                new InternalSingleBucketAggregation("sb", type, 3, InternalAggregations.EMPTY, null));
        }
    }

    @Test
    public void rareTermsRoundTrips() throws IOException {
        List<TermsBucket> ba = List.of(new TermsBucket("rare1", 1, 0, InternalAggregations.EMPTY));
        List<TermsBucket> bb = List.of(new TermsBucket("rare2", 1, 0, InternalAggregations.EMPTY));
        checkRoundTripReduce(new InternalRareTerms("rt", ba, 1, null), new InternalRareTerms("rt", bb, 1, null));
    }

    @Test
    public void significantTermsRoundTrips() throws IOException {
        List<SignificantTermsBucket> ba = List.of(new SignificantTermsBucket("sig1", 5, 10, 2.5, InternalAggregations.EMPTY));
        List<SignificantTermsBucket> bb = List.of(new SignificantTermsBucket("sig1", 3, 10, 1.5, InternalAggregations.EMPTY));
        checkRoundTripReduce(
            new InternalSignificantTerms("st", ba, 100, 1000, SignificanceHeuristic.JLH, s -> 0L, 10, 1, null),
            new InternalSignificantTerms("st", bb, 50, 1000, SignificanceHeuristic.JLH, s -> 0L, 10, 1, null));
    }

    @Test
    public void compositeRoundTrips() throws IOException {
        List<CompositeBucket> ba = List.of(new CompositeBucket(List.of("k"), List.of("v1"), 2, InternalAggregations.EMPTY));
        List<CompositeBucket> bb = List.of(new CompositeBucket(List.of("k"), List.of("v1"), 5, InternalAggregations.EMPTY));
        checkRoundTripReduce(new InternalComposite("comp", ba, 10, List.of(true), null),
            new InternalComposite("comp", bb, 10, List.of(true), null));
    }

    @Test
    public void termsWithNestedAvgSubAggregationRoundTrips() throws IOException {
        InternalAggregations subA = new InternalAggregations(List.of(new InternalAvg("avg_field", 10.0, 2, null)));
        InternalAggregations subB = new InternalAggregations(List.of(new InternalAvg("avg_field", 30.0, 3, null)));
        List<TermsBucket> ba = List.of(new TermsBucket("bucket1", 2, 0, subA));
        List<TermsBucket> bb = List.of(new TermsBucket("bucket1", 3, 0, subB));
        InternalTerms termsA = new InternalTerms("terms_field", ba, 0, 10, 1, TermsOrder.count(false), false, null);
        InternalTerms termsB = new InternalTerms("terms_field", bb, 0, 10, 1, TermsOrder.count(false), false, null);
        checkRoundTripReduce(termsA, termsB);
    }

    @Test
    public void dateHistogramRoundTrips() throws IOException {
        List<DateHistogramBucket> ba = List.of(new DateHistogramBucket(0L, 2, InternalAggregations.EMPTY));
        List<DateHistogramBucket> bb = List.of(new DateHistogramBucket(0L, 3, InternalAggregations.EMPTY));
        checkRoundTripReduce(new InternalDateHistogram("dh", ba, null, 86400000L, ZoneId.of("UTC"), 0, 1, null, null, null),
            new InternalDateHistogram("dh", bb, null, 86400000L, ZoneId.of("UTC"), 0, 1, null, null, null));
    }
}
