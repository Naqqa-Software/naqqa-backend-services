package com.naqqa.elasticsearch.search.aggs.pipeline;

import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.MultiBucketsAggregation;

import java.util.ArrayList;
import java.util.List;

public final class DerivativeAggregator {

    private DerivativeAggregator() {
    }

    public static InternalAggregation apply(MultiBucketsAggregation parent, String bucketsPath, String outputName) {
        List<? extends MultiBucketsAggregation.Bucket> buckets = parent.getBuckets();
        double[] values = new double[buckets.size()];
        for (int i = 0; i < buckets.size(); i++) {
            values[i] = PipelineUtil.resolveValue(buckets.get(i), bucketsPath);
        }
        List<MultiBucketsAggregation.Bucket> updated = new ArrayList<>(buckets.size());
        Double previous = null;
        for (int i = 0; i < buckets.size(); i++) {
            MultiBucketsAggregation.Bucket b = buckets.get(i);
            if (Double.isNaN(values[i])) {
                updated.add(b);
                continue;
            }
            if (previous == null) {
                updated.add(b);
            } else {
                updated.add(PipelineUtil.withExtraMetric(b, new InternalSimpleValue(outputName, values[i] - previous, null)));
            }
            previous = values[i];
        }
        return parent.withBuckets(updated);
    }
}
