package com.naqqa.elasticsearch.search.aggs;

import com.naqqa.elasticsearch.search.aggs.metrics.BoxplotAggregator;
import com.naqqa.elasticsearch.search.aggs.metrics.CardinalityAggregator;
import com.naqqa.elasticsearch.search.aggs.metrics.ExtendedStatsAggregator;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalBoxplot;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalCardinality;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalExtendedStats;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalMatrixStats;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalPercentiles;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalStats;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalStringStats;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalWeightedAvg;
import com.naqqa.elasticsearch.search.aggs.metrics.MatrixStatsAggregator;
import com.naqqa.elasticsearch.search.aggs.metrics.MedianAbsoluteDeviationAggregator;
import com.naqqa.elasticsearch.search.aggs.metrics.PercentilesAggregator;
import com.naqqa.elasticsearch.search.aggs.metrics.PercentilesMethod;
import com.naqqa.elasticsearch.search.aggs.metrics.StatsAggregator;
import com.naqqa.elasticsearch.search.aggs.metrics.StringStatsAggregator;
import com.naqqa.elasticsearch.search.aggs.metrics.WeightedAvgAggregator;
import com.naqqa.elasticsearch.search.aggs.support.sketch.MedianAbsoluteDeviation;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public final class MetricsTest {

    private static FakeValuesLookup doubles(double... values) {
        Map<Integer, double[]> docValues = new HashMap<>();
        for (int i = 0; i < values.length; i++) {
            docValues.put(i, new double[]{values[i]});
        }
        return new FakeValuesLookup().putDoubles("f", docValues);
    }

    @Test
    public void statsMatchesHandComputedValues() {
        double[] values = {2, 4, 4, 4, 5, 5, 7, 9};
        FakeValuesLookup lookup = doubles(values);
        StatsAggregator agg = new StatsAggregator("s", lookup.doubleValues("f"));
        for (int i = 0; i < values.length; i++) {
            agg.collect(i, 0);
        }
        InternalStats stats = (InternalStats) agg.buildAggregation(0);
        Assert.assertEquals(8, stats.count());
        Assert.assertEquals(40.0, stats.sum(), 1e-9);
        Assert.assertEquals(2.0, stats.min(), 1e-9);
        Assert.assertEquals(9.0, stats.max(), 1e-9);
        Assert.assertEquals(5.0, stats.avg(), 1e-9);
    }

    @Test
    public void extendedStatsComputesPopulationVariance() {
        double[] values = {2, 4, 4, 4, 5, 5, 7, 9};
        FakeValuesLookup lookup = doubles(values);
        ExtendedStatsAggregator agg = new ExtendedStatsAggregator("es", lookup.doubleValues("f"), 2.0);
        for (int i = 0; i < values.length; i++) {
            agg.collect(i, 0);
        }
        InternalExtendedStats es = (InternalExtendedStats) agg.buildAggregation(0);
        Assert.assertEquals(4.0, es.variancePopulation(), 1e-9);
        Assert.assertEquals(2.0, es.stdDeviation(), 1e-9);
    }

    @Test
    public void weightedAvgMatchesHandComputedValue() {
        Map<Integer, double[]> valueDocs = new HashMap<>();
        Map<Integer, double[]> weightDocs = new HashMap<>();
        valueDocs.put(0, new double[]{10});
        weightDocs.put(0, new double[]{1});
        valueDocs.put(1, new double[]{20});
        weightDocs.put(1, new double[]{3});
        FakeValuesLookup lookup = new FakeValuesLookup().putDoubles("v", valueDocs).putDoubles("w", weightDocs);
        WeightedAvgAggregator agg = new WeightedAvgAggregator("wa", lookup.doubleValues("v"), lookup.doubleValues("w"));
        agg.collect(0, 0);
        agg.collect(1, 0);
        InternalWeightedAvg result = (InternalWeightedAvg) agg.buildAggregation(0);
        Assert.assertEquals((10 * 1 + 20 * 3) / 4.0, result.value(), 1e-9);
    }

    @Test
    public void medianAbsoluteDeviationMatchesExactWithinTolerance() {
        double[] values = {1, 1, 2, 2, 4, 6, 9};
        FakeValuesLookup lookup = doubles(values);
        MedianAbsoluteDeviationAggregator agg = new MedianAbsoluteDeviationAggregator("mad", lookup.doubleValues("f"), 100.0);
        for (int i = 0; i < values.length; i++) {
            agg.collect(i, 0);
        }
        double approx = agg.buildAggregation(0) instanceof com.naqqa.elasticsearch.search.aggs.metrics.InternalMedianAbsoluteDeviation m ? m.value() : Double.NaN;
        double exact = MedianAbsoluteDeviation.exact(values);
        Assert.assertEquals(exact, approx, 0.5);
    }

    @Test
    public void cardinalityIsWithinExpectedErrorBound() {
        Map<Integer, double[]> docValues = new HashMap<>();
        int n = 5000;
        for (int i = 0; i < n; i++) {
            docValues.put(i, new double[]{i});
        }
        FakeValuesLookup lookup = new FakeValuesLookup().putDoubles("f", docValues);
        CardinalityAggregator agg = new CardinalityAggregator("card", lookup.doubleValues("f"), null, 14);
        for (int i = 0; i < n; i++) {
            agg.collect(i, 0);
        }
        InternalCardinality result = (InternalCardinality) agg.buildAggregation(0);
        long estimate = result.cardinality();
        double error = Math.abs(estimate - n) / (double) n;
        Assert.assertTrue(error < 0.05, "cardinality error too high: " + error + " estimate=" + estimate);
    }

    @Test
    public void percentilesTdigestCloseToExactSortedValues() {
        double[] values = new double[1000];
        for (int i = 0; i < values.length; i++) {
            values[i] = i;
        }
        FakeValuesLookup lookup = doubles(values);
        PercentilesAggregator agg = new PercentilesAggregator("p", lookup.doubleValues("f"), PercentilesMethod.TDIGEST,
            new double[]{50, 95, 99}, 100.0, 3);
        for (int i = 0; i < values.length; i++) {
            agg.collect(i, 0);
        }
        InternalPercentiles result = (InternalPercentiles) agg.buildAggregation(0);
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        double p50Exact = sorted[500];
        double p95Exact = sorted[950];
        Assert.assertTrue(Math.abs(result.percentile(50) - p50Exact) < 20, "p50 off: " + result.percentile(50));
        Assert.assertTrue(Math.abs(result.percentile(95) - p95Exact) < 20, "p95 off: " + result.percentile(95));
    }

    @Test
    public void percentilesHdrCloseToExactSortedValues() {
        double[] values = new double[1000];
        for (int i = 0; i < values.length; i++) {
            values[i] = i + 1;
        }
        FakeValuesLookup lookup = doubles(values);
        PercentilesAggregator agg = new PercentilesAggregator("p", lookup.doubleValues("f"), PercentilesMethod.HDR,
            new double[]{50, 99}, 100.0, 3);
        for (int i = 0; i < values.length; i++) {
            agg.collect(i, 0);
        }
        InternalPercentiles result = (InternalPercentiles) agg.buildAggregation(0);
        Assert.assertTrue(Math.abs(result.percentile(50) - 500) < 10, "p50 off: " + result.percentile(50));
    }

    @Test
    public void boxplotComputesQuartiles() {
        double[] values = new double[100];
        for (int i = 0; i < values.length; i++) {
            values[i] = i + 1;
        }
        FakeValuesLookup lookup = doubles(values);
        BoxplotAggregator agg = new BoxplotAggregator("bp", lookup.doubleValues("f"), 100.0);
        for (int i = 0; i < values.length; i++) {
            agg.collect(i, 0);
        }
        InternalBoxplot result = (InternalBoxplot) agg.buildAggregation(0);
        Assert.assertTrue(Math.abs(result.stats().q2() - 50.5) < 5, "median off: " + result.stats().q2());
    }

    @Test
    public void matrixStatsComputesCorrelation() {
        Map<Integer, double[]> xDocs = new HashMap<>();
        Map<Integer, double[]> yDocs = new HashMap<>();
        for (int i = 0; i < 20; i++) {
            xDocs.put(i, new double[]{i});
            yDocs.put(i, new double[]{2.0 * i});
        }
        FakeValuesLookup lookup = new FakeValuesLookup().putDoubles("x", xDocs).putDoubles("y", yDocs);
        MatrixStatsAggregator agg = new MatrixStatsAggregator("ms", new String[]{"x", "y"},
            new com.naqqa.elasticsearch.search.aggs.support.DoubleValuesSource[]{lookup.doubleValues("x"), lookup.doubleValues("y")});
        for (int i = 0; i < 20; i++) {
            agg.collect(i, 0);
        }
        InternalMatrixStats result = (InternalMatrixStats) agg.buildAggregation(0);
        double correlation = result.state().correlation(0, 1);
        Assert.assertEquals(1.0, correlation, 1e-6);
    }

    @Test
    public void stringStatsComputesLengthsAndEntropy() {
        Map<Integer, String[]> docs = new HashMap<>();
        docs.put(0, new String[]{"aa"});
        docs.put(1, new String[]{"bbbb"});
        FakeValuesLookup lookup = new FakeValuesLookup().putStrings("f", docs);
        StringStatsAggregator agg = new StringStatsAggregator("ss", lookup.bytesValues("f"), false);
        agg.collect(0, 0);
        agg.collect(1, 0);
        InternalStringStats result = (InternalStringStats) agg.buildAggregation(0);
        Map<String, Object> map = result.toMap();
        Assert.assertEquals(2L, map.get("count"));
        Assert.assertEquals(3.0, (Double) map.get("avg_length"), 1e-9);
    }
}
