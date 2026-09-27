package com.naqqa.elasticsearch.search.aggs.pipeline;

import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.MultiBucketsAggregation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MovingPercentilesAggregator {

    private MovingPercentilesAggregator() {
    }

    public static InternalAggregation apply(MultiBucketsAggregation parent, String bucketsPath, String outputName, int window, double percentile) {
        List<? extends MultiBucketsAggregation.Bucket> buckets = parent.getBuckets();
        double[] values = new double[buckets.size()];
        for (int i = 0; i < buckets.size(); i++) {
            values[i] = PipelineUtil.resolveValue(buckets.get(i), bucketsPath);
        }
        List<MultiBucketsAggregation.Bucket> updated = new ArrayList<>(buckets.size());
        for (int i = 0; i < buckets.size(); i++) {
            List<Double> windowValues = new ArrayList<>();
            for (int j = Math.max(0, i - window); j < i; j++) {
                if (!Double.isNaN(values[j])) {
                    windowValues.add(values[j]);
                }
            }
            MultiBucketsAggregation.Bucket b = buckets.get(i);
            if (windowValues.isEmpty()) {
                updated.add(b);
            } else {
                Collections.sort(windowValues);
                double pos = (percentile / 100.0) * (windowValues.size() - 1);
                int lo = (int) Math.floor(pos);
                int hi = Math.min(lo + 1, windowValues.size() - 1);
                double frac = pos - lo;
                double result = windowValues.get(lo) + (windowValues.get(hi) - windowValues.get(lo)) * frac;
                updated.add(PipelineUtil.withExtraMetric(b, new InternalSimpleValue(outputName, result, null)));
            }
        }
        return parent.withBuckets(updated);
    }
}
