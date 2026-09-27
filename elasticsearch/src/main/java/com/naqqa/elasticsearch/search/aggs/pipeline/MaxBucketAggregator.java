package com.naqqa.elasticsearch.search.aggs.pipeline;

import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.MultiBucketsAggregation;

import java.util.ArrayList;
import java.util.List;

public final class MaxBucketAggregator {

    private MaxBucketAggregator() {
    }

    public static InternalAggregation apply(MultiBucketsAggregation parent, String bucketsPath, String outputName) {
        double best = Double.NEGATIVE_INFINITY;
        List<String> keys = new ArrayList<>();
        for (MultiBucketsAggregation.Bucket b : parent.getBuckets()) {
            double v = PipelineUtil.resolveValue(b, bucketsPath);
            if (Double.isNaN(v)) {
                continue;
            }
            if (v > best) {
                best = v;
                keys.clear();
                keys.add(b.getKeyAsString());
            } else if (v == best) {
                keys.add(b.getKeyAsString());
            }
        }
        return new InternalBucketMetricValue(outputName, Double.isInfinite(best) ? Double.NaN : best, keys, null);
    }
}
