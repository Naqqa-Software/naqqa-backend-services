package com.naqqa.elasticsearch.search.aggs.support.sketch;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

import com.naqqa.elasticsearch.test.Test;
import java.util.SplittableRandom;

public class TDigestHdrTTestTest {

    @Test
    public void tDigestQuantileVsExact() {
        SplittableRandom r = new SplittableRandom(7);
        double[] values = new double[20000];
        TDigest digest = new TDigest(100);
        for (int i = 0; i < values.length; i++) {
            values[i] = r.nextDouble() * 1000;
            digest.add(values[i]);
        }
        double[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        for (double q : new double[] {0.01, 0.1, 0.5, 0.9, 0.99}) {
            double exact = sorted[(int) (q * (sorted.length - 1))];
            double approx = digest.quantile(q);
            double tolerance = 30.0;
            assertTrue(Math.abs(exact - approx) < tolerance, "q=" + q + " exact=" + exact + " approx=" + approx);
        }
        assertTrue(digest.cdf(digest.getMin()) < 1e-3, "cdf(min) should be near 0");
        assertTrue(digest.cdf(digest.getMax()) > 1 - 1e-3, "cdf(max) should be near 1");
    }

    @Test
    public void tDigestMergeMatchesCombined() {
        TDigest a = new TDigest(100);
        TDigest b = new TDigest(100);
        TDigest combined = new TDigest(100);
        for (int i = 0; i < 5000; i++) {
            a.add(i);
            combined.add(i);
        }
        for (int i = 5000; i < 8000; i++) {
            b.add(i);
            combined.add(i);
        }
        TDigest merged = TDigest.mergeAll(100, java.util.List.of(a, b));
        assertEquals(combined.quantile(0.5), merged.quantile(0.5), 5.0);
        assertEquals(combined.quantile(0.99), merged.quantile(0.99), 20.0);
    }

    @Test
    public void hdrHistogramPercentilesVsExact() {
        HdrHistogram h = new HdrHistogram(3600L * 1000 * 1000, 3);
        int n = 10000;
        for (int i = 1; i <= n; i++) {
            h.recordValue(i);
        }
        long p50 = h.getValueAtPercentile(50.0);
        long p99 = h.getValueAtPercentile(99.0);
        assertTrue(Math.abs(p50 - 5000) <= 10, "p50=" + p50);
        assertTrue(Math.abs(p99 - 9900) <= 15, "p99=" + p99);
        double pctAt5000 = h.getPercentileAtOrBelowValue(5000);
        assertTrue(Math.abs(pctAt5000 - 50.0) < 1.0, "pct=" + pctAt5000);
        assertEquals((long) n, h.getTotalCount());
    }

    @Test
    public void hdrHistogramMergeMatchesCombined() {
        HdrHistogram a = new HdrHistogram(100000L, 3);
        HdrHistogram b = new HdrHistogram(100000L, 3);
        HdrHistogram combined = new HdrHistogram(100000L, 3);
        for (int i = 1; i <= 1000; i++) {
            a.recordValue(i);
            combined.recordValue(i);
        }
        for (int i = 1001; i <= 2000; i++) {
            b.recordValue(i);
            combined.recordValue(i);
        }
        a.add(b);
        assertEquals(combined.getTotalCount(), a.getTotalCount());
        assertEquals(combined.getValueAtPercentile(50.0), a.getValueAtPercentile(50.0));
    }

    @Test
    public void doubleHdrHistogramTracksFractionalValues() {
        DoubleHdrHistogram d = new DoubleHdrHistogram(3);
        for (int i = 1; i <= 1000; i++) {
            d.recordValue(i * 0.01);
        }
        double p50 = d.getValueAtPercentile(50.0);
        assertTrue(Math.abs(p50 - 5.0) < 0.05, "p50=" + p50);
    }

    @Test
    public void tTestPairedDetectsDifference() {
        TTest t = new TTest(TTest.Type.PAIRED, 2);
        SplittableRandom r = new SplittableRandom(5);
        for (int i = 0; i < 200; i++) {
            double base = r.nextDouble() * 10;
            t.addPair(base + 5.0, base);
        }
        TTest.Result result = t.result();
        assertTrue(result.pValue() < 0.01, "pValue=" + result.pValue());
        assertTrue(result.t() > 0);
    }

    @Test
    public void tTestHomoscedasticNoDifference() {
        TTest t = new TTest(TTest.Type.HOMOSCEDASTIC, 2);
        SplittableRandom r = new SplittableRandom(9);
        for (int i = 0; i < 500; i++) {
            t.addA(r.nextDouble());
            t.addB(r.nextDouble());
        }
        TTest.Result result = t.result();
        assertTrue(result.pValue() > 0.05, "pValue=" + result.pValue());
    }

    @Test
    public void boxplotAndMadOnKnownData() {
        double[] values = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        TDigest digest = new TDigest(200);
        for (double v : values) {
            digest.add(v);
        }
        Boxplot.Stats stats = Boxplot.compute(digest);
        assertEquals(1.0, stats.min(), 1e-9);
        assertEquals(10.0, stats.max(), 1e-9);
        assertTrue(stats.q1() < stats.q2() && stats.q2() < stats.q3());

        double madExact = MedianAbsoluteDeviation.exact(values);
        double madApprox = MedianAbsoluteDeviation.compute(digest);
        assertTrue(Math.abs(madExact - madApprox) < 1.5, "exact=" + madExact + " approx=" + madApprox);
    }

    @Test
    public void reservoirSamplerStatsAndMerge() {
        ReservoirSampler r = new ReservoirSampler(500, 42);
        for (int i = 1; i <= 5000; i++) {
            r.add(i);
        }
        assertEquals(5000L, r.count());
        assertEquals(1.0, r.min(), 1e-9);
        assertEquals(5000.0, r.max(), 1e-9);
        double median = r.quantile(0.5);
        assertTrue(Math.abs(median - 2500) < 400, "median=" + median);

        ReservoirSampler a = new ReservoirSampler(300, 1);
        ReservoirSampler b = new ReservoirSampler(300, 2);
        for (int i = 0; i < 1000; i++) {
            a.add(i);
        }
        for (int i = 1000; i < 2000; i++) {
            b.add(i);
        }
        a.merge(b);
        assertEquals(2000L, a.count());
        assertEquals(0.0, a.min(), 1e-9);
        assertEquals(1999.0, a.max(), 1e-9);
    }
}
