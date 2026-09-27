package com.naqqa.elasticsearch.search.aggs.pipeline;

import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.MultiBucketsAggregation;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalAvg;

public final class AvgBucketAggregator {

    private AvgBucketAggregator() {
    }

    public static InternalAggregation apply(MultiBucketsAggregation parent, String bucketsPath, String outputName) {
        double sum = 0;
        long count = 0;
        for (MultiBucketsAggregation.Bucket b : parent.getBuckets()) {
            double v = PipelineUtil.resolveValue(b, bucketsPath);
            if (!Double.isNaN(v)) {
                sum += v;
                count++;
            }
        }
        return new InternalAvg(outputName, sum, count, null);
    }
}
