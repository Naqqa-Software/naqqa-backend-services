package com.naqqa.elasticsearch.search.aggs.support.sketch;

public final class TDistribution {

    private static final double[] LANCZOS = {
        0.99999999999980993,
        676.5203681218851,
        -1259.1392167224028,
        771.32342877765313,
        -176.61502916214059,
        12.507343278686905,
        -0.13857109526572012,
        9.9843695780195716e-6,
        1.5056327351493116e-7
    };
    private static final double LANCZOS_G = 7.0;
    private static final double HALF_LOG_2PI = 0.5 * Math.log(2 * Math.PI);
    private static final int MAX_ITERATIONS = 10000;
    private static final double EPSILON = 1e-15;
    private static final double FPMIN = 1e-300;

    private final double degreesOfFreedom;

    public TDistribution(double degreesOfFreedom) {
        if (!(degreesOfFreedom > 0)) {
            throw new IllegalArgumentException("degrees of freedom must be positive, got " + degreesOfFreedom);
        }
        this.degreesOfFreedom = degreesOfFreedom;
    }

    public double degreesOfFreedom() {
        return degreesOfFreedom;
    }

    public double cumulativeProbability(double t) {
        return cdf(t, degreesOfFreedom);
    }

    public double density(double t) {
        double v = degreesOfFreedom;
        double logD = logGamma((v + 1) / 2) - logGamma(v / 2) - 0.5 * Math.log(v * Math.PI) - (v + 1) / 2 * Math.log1p(t * t / v);
        return Math.exp(logD);
    }

    public static double cdf(double t, double degreesOfFreedom) {
        if (!(degreesOfFreedom > 0)) {
            throw new IllegalArgumentException("degrees of freedom must be positive, got " + degreesOfFreedom);
        }
        if (Double.isNaN(t)) {
            return Double.NaN;
        }
        if (t == Double.POSITIVE_INFINITY) {
            return 1.0;
        }
        if (t == Double.NEGATIVE_INFINITY) {
            return 0.0;
        }
        if (t == 0) {
            return 0.5;
        }
        double x = degreesOfFreedom / (degreesOfFreedom + t * t);
        double tail = 0.5 * regularizedIncompleteBeta(x, degreesOfFreedom / 2.0, 0.5);
        return t > 0 ? 1.0 - tail : tail;
    }

    public static double logGamma(double x) {
        if (Double.isNaN(x)) {
            return Double.NaN;
        }
        if (x <= 0 && x == Math.rint(x)) {
            return Double.POSITIVE_INFINITY;
        }
        if (x < 0.5) {
            return Math.log(Math.PI / Math.abs(Math.sin(Math.PI * x))) - logGamma(1.0 - x);
        }
        double z = x - 1.0;
        double a = LANCZOS[0];
        double t = z + LANCZOS_G + 0.5;
        for (int i = 1; i < LANCZOS.length; i++) {
            a += LANCZOS[i] / (z + i);
        }
        return HALF_LOG_2PI + (z + 0.5) * Math.log(t) - t + Math.log(a);
    }

    public static double logBeta(double a, double b) {
        return logGamma(a) + logGamma(b) - logGamma(a + b);
    }

    public static double regularizedIncompleteBeta(double x, double a, double b) {
        if (Double.isNaN(x) || Double.isNaN(a) || Double.isNaN(b) || !(a > 0) || !(b > 0)) {
            return Double.NaN;
        }
        if (x <= 0) {
            return 0.0;
        }
        if (x >= 1) {
            return 1.0;
        }
        double logFront = a * Math.log(x) + b * Math.log1p(-x) - logBeta(a, b);
        double front = Math.exp(logFront);
        if (x < (a + 1.0) / (a + b + 2.0)) {
            return front * continuedFraction(x, a, b) / a;
        }
        return 1.0 - front * continuedFraction(1.0 - x, b, a) / b;
    }

    private static double continuedFraction(double x, double a, double b) {
        double qab = a + b;
        double qap = a + 1.0;
        double qam = a - 1.0;
        double c = 1.0;
        double d = 1.0 - qab * x / qap;
        if (Math.abs(d) < FPMIN) {
            d = FPMIN;
        }
        d = 1.0 / d;
        double h = d;
        for (int m = 1; m <= MAX_ITERATIONS; m++) {
            int m2 = 2 * m;
            double aa = m * (b - m) * x / ((qam + m2) * (a + m2));
            d = 1.0 + aa * d;
            if (Math.abs(d) < FPMIN) {
                d = FPMIN;
            }
            c = 1.0 + aa / c;
            if (Math.abs(c) < FPMIN) {
                c = FPMIN;
            }
            d = 1.0 / d;
            h *= d * c;
            aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2));
            d = 1.0 + aa * d;
            if (Math.abs(d) < FPMIN) {
                d = FPMIN;
            }
            c = 1.0 + aa / c;
            if (Math.abs(c) < FPMIN) {
                c = FPMIN;
            }
            d = 1.0 / d;
            double del = d * c;
            h *= del;
            if (Math.abs(del - 1.0) < EPSILON) {
                return h;
            }
        }
        return h;
    }
}
