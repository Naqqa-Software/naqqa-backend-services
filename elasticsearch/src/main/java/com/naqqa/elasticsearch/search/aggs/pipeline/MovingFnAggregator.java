package com.naqqa.elasticsearch.search.aggs.pipeline;

import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.MultiBucketsAggregation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public final class MovingFnAggregator {

    private MovingFnAggregator() {
    }

    public static InternalAggregation apply(MultiBucketsAggregation parent, String bucketsPath, String outputName, int window, Function<List<Double>, Double> windowFunction) {
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
                Double result = windowFunction.apply(windowValues);
                updated.add(PipelineUtil.withExtraMetric(b, new InternalSimpleValue(outputName, result == null ? Double.NaN : result, null)));
            }
        }
        return parent.withBuckets(updated);
    }
}
