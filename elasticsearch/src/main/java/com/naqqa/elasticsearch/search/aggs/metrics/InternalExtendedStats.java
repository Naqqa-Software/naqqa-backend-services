package com.naqqa.elasticsearch.search.aggs.metrics;

import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.ReduceContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InternalExtendedStats extends InternalStats {

    private final double sumOfSquares;
    private final double sigma;

    public InternalExtendedStats(String name, long count, double sum, double min, double max, double sumOfSquares, double sigma, Map<String, Object> metadata) {
        super(name, count, sum, min, max, metadata);
        this.sumOfSquares = sumOfSquares;
        this.sigma = sigma;
    }

    public double sumOfSquares() {
        return sumOfSquares;
    }

    public double sigmaValue() {
        return sigma;
    }

    public double variance() {
        if (count == 0) {
            return Double.NaN;
        }
        double v = (sumOfSquares - (sum * sum) / count) / count;
        return Math.max(0, v);
    }

    public double variancePopulation() {
        return variance();
    }

    public double varianceSampling() {
        if (count < 2) {
            return Double.NaN;
        }
        double v = (sumOfSquares - (sum * sum) / count) / (count - 1);
        return Math.max(0, v);
    }

    public double stdDeviation() {
        return Math.sqrt(variance());
    }

    public double stdDeviationSampling() {
        return Math.sqrt(varianceSampling());
    }

    @Override
    public String getType() {
        return "extended_stats";
    }

    @Override
    public InternalAggregation reduce(List<InternalAggregation> aggregations, ReduceContext context) {
        long countAcc = 0;
        double sumAcc = 0;
        double minAcc = Double.POSITIVE_INFINITY;
        double maxAcc = Double.NEGATIVE_INFINITY;
        double sosAcc = 0;
        for (InternalAggregation a : aggregations) {
            InternalExtendedStats s = (InternalExtendedStats) a;
            countAcc += s.count;
            sumAcc += s.sum;
            minAcc = Math.min(minAcc, s.min);
            maxAcc = Math.max(maxAcc, s.max);
            sosAcc += s.sumOfSquares;
        }
        return new InternalExtendedStats(getName(), countAcc, sumAcc, minAcc, maxAcc, sosAcc, sigma, getMetadata());
    }

    @Override
    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>(super.toMap());
        double std = stdDeviation();
        double avg = avg();
        map.put("sum_of_squares", sumOfSquares);
        map.put("variance", count == 0 ? null : variance());
        map.put("variance_population", count == 0 ? null : variancePopulation());
        map.put("variance_sampling", count < 2 ? null : varianceSampling());
        map.put("std_deviation", count == 0 ? null : std);
        map.put("std_deviation_population", count == 0 ? null : std);
        map.put("std_deviation_sampling", count < 2 ? null : stdDeviationSampling());
        LinkedHashMap<String, Object> bounds = new LinkedHashMap<>();
        bounds.put("upper", count == 0 ? null : avg + sigma * std);
        bounds.put("lower", count == 0 ? null : avg - sigma * std);
        bounds.put("upper_population", bounds.get("upper"));
        bounds.put("lower_population", bounds.get("lower"));
        double stdSampling = count < 2 ? Double.NaN : stdDeviationSampling();
        bounds.put("upper_sampling", count < 2 ? null : avg + sigma * stdSampling);
        bounds.put("lower_sampling", count < 2 ? null : avg - sigma * stdSampling);
        map.put("std_deviation_bounds", bounds);
        return map;
    }
}
