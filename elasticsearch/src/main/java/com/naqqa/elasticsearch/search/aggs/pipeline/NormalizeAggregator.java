package com.naqqa.elasticsearch.search.aggs.pipeline;

import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.MultiBucketsAggregation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class NormalizeAggregator {

    public enum Method {
        RESCALE_0_1, PERCENT_OF_SUM, MEAN, ZSCORE, SOFTMAX;

        public static Method fromString(String s) {
            return switch (s.toLowerCase(Locale.ROOT)) {
                case "rescale_0_1" -> RESCALE_0_1;
                case "percent_of_sum" -> PERCENT_OF_SUM;
                case "mean" -> MEAN;
                case "zscore" -> ZSCORE;
                case "softmax" -> SOFTMAX;
                default -> throw new IllegalArgumentException("unknown normalize method [" + s + "]");
            };
        }
    }

    private NormalizeAggregator() {
    }

    public static InternalAggregation apply(MultiBucketsAggregation parent, String bucketsPath, String outputName, Method method) {
        List<? extends MultiBucketsAggregation.Bucket> buckets = parent.getBuckets();
        double[] values = new double[buckets.size()];
        for (int i = 0; i < buckets.size(); i++) {
            values[i] = PipelineUtil.resolveValue(buckets.get(i), bucketsPath);
        }
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        double sum = 0;
        double sumExp = 0;
        int n = 0;
        for (double v : values) {
            if (Double.isNaN(v)) {
                continue;
            }
            min = Math.min(min, v);
            max = Math.max(max, v);
            sum += v;
            sumExp += Math.exp(v);
            n++;
        }
        double mean = n == 0 ? 0 : sum / n;
        double variance = 0;
        if (method == Method.ZSCORE && n > 0) {
            for (double v : values) {
                if (!Double.isNaN(v)) {
                    variance += (v - mean) * (v - mean);
                }
            }
            variance /= n;
        }
        double stdDev = Math.sqrt(variance);
        List<MultiBucketsAggregation.Bucket> updated = new ArrayList<>(buckets.size());
        for (int i = 0; i < buckets.size(); i++) {
            MultiBucketsAggregation.Bucket b = buckets.get(i);
            double v = values[i];
            if (Double.isNaN(v)) {
                updated.add(b);
                continue;
            }
            double result = switch (method) {
                case RESCALE_0_1 -> max == min ? 0 : (v - min) / (max - min);
                case PERCENT_OF_SUM -> sum == 0 ? 0 : v / sum;
                case MEAN -> v - mean;
                case ZSCORE -> stdDev == 0 ? 0 : (v - mean) / stdDev;
                case SOFTMAX -> sumExp == 0 ? 0 : Math.exp(v) / sumExp;
            };
            updated.add(PipelineUtil.withExtraMetric(b, new InternalSimpleValue(outputName, result, null)));
        }
        return parent.withBuckets(updated);
    }
}
