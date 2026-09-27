package com.naqqa.elasticsearch.search.aggs.pipeline;

import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.MultiBucketsAggregation;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalSum;

public final class SumBucketAggregator {

    private SumBucketAggregator() {
    }

    public static InternalAggregation apply(MultiBucketsAggregation parent, String bucketsPath, String outputName) {
        double sum = 0;
        for (MultiBucketsAggregation.Bucket b : parent.getBuckets()) {
            double v = PipelineUtil.resolveValue(b, bucketsPath);
            if (!Double.isNaN(v)) {
                sum += v;
            }
        }
        return new InternalSum(outputName, sum, null);
    }
}
