package com.naqqa.elasticsearch.search.aggs.pipeline;

import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.MultiBucketsAggregation;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalExtendedStats;

public final class ExtendedStatsBucketAggregator {

    private ExtendedStatsBucketAggregator() {
    }

    public static InternalAggregation apply(MultiBucketsAggregation parent, String bucketsPath, String outputName, double sigma) {
        long count = 0;
        double sum = 0;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        double sumOfSquares = 0;
        for (MultiBucketsAggregation.Bucket b : parent.getBuckets()) {
            double v = PipelineUtil.resolveValue(b, bucketsPath);
            if (Double.isNaN(v)) {
                continue;
            }
            count++;
            sum += v;
            sumOfSquares += v * v;
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        return new InternalExtendedStats(outputName, count, sum, min, max, sumOfSquares, sigma, null);
    }
}
