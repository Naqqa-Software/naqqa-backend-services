package com.naqqa.elasticsearch.search.aggs.support.sketch;

import java.util.List;

public final class Boxplot {

    public record Stats(double min, double max, double q1, double q2, double q3, double lower, double upper) {
        public double iqr() {
            return q3 - q1;
        }
    }

    private Boxplot() {
    }

    public static Stats compute(TDigest digest) {
        if (digest.totalWeight() == 0) {
            double nan = Double.NaN;
            return new Stats(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, nan, nan, nan, nan, nan);
        }
        double q1 = digest.quantile(0.25);
        double q2 = digest.quantile(0.5);
        double q3 = digest.quantile(0.75);
        double[] whiskers = whiskers(digest, q1, q3);
        return new Stats(digest.getMin(), digest.getMax(), q1, q2, q3, whiskers[0], whiskers[1]);
    }

    public static double[] whiskers(TDigest digest) {
        return whiskers(digest, digest.quantile(0.25), digest.quantile(0.75));
    }

    private static double[] whiskers(TDigest digest, double q1, double q3) {
        double iqr = q3 - q1;
        double lowerFence = q1 - 1.5 * iqr;
        double upperFence = q3 + 1.5 * iqr;
        double min = digest.getMin();
        double max = digest.getMax();
        double lower;
        if (min >= lowerFence) {
            lower = min;
        } else {
            lower = q1;
            List<TDigest.Centroid> centroids = digest.centroids();
            for (TDigest.Centroid c : centroids) {
                if (c.mean() >= lowerFence) {
                    lower = Math.min(c.mean(), q1);
                    break;
                }
            }
        }
        double upper;
        if (max <= upperFence) {
            upper = max;
        } else {
            upper = q3;
            List<TDigest.Centroid> centroids = digest.centroids();
            for (int i = centroids.size() - 1; i >= 0; i--) {
                double m = centroids.get(i).mean();
                if (m <= upperFence) {
                    upper = Math.max(m, q3);
                    break;
                }
            }
        }
        return new double[] {lower, upper};
    }
}
