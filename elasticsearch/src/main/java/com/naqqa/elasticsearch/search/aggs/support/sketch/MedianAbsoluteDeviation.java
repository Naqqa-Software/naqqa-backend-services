package com.naqqa.elasticsearch.search.aggs.support.sketch;

public final class MedianAbsoluteDeviation {

    private MedianAbsoluteDeviation() {
    }

    public static double compute(TDigest valuesSketch) {
        if (valuesSketch.size() == 0 && valuesSketch.totalWeight() == 0) {
            return Double.NaN;
        }
        double approximateMedian = valuesSketch.quantile(0.5);
        TDigest deviations = new TDigest(valuesSketch.compression());
        for (TDigest.Centroid centroid : valuesSketch.centroids()) {
            deviations.add(Math.abs(approximateMedian - centroid.mean()), centroid.weight());
        }
        return deviations.quantile(0.5);
    }

    public static double exact(double[] values) {
        if (values.length == 0) {
            return Double.NaN;
        }
        double[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        double median = medianOfSorted(sorted);
        double[] dev = new double[sorted.length];
        for (int i = 0; i < sorted.length; i++) {
            dev[i] = Math.abs(sorted[i] - median);
        }
        java.util.Arrays.sort(dev);
        return medianOfSorted(dev);
    }

    private static double medianOfSorted(double[] sorted) {
        int n = sorted.length;
        if ((n & 1) == 1) {
            return sorted[n / 2];
        }
        return (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
    }
}
