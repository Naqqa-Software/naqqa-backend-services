package com.naqqa.elasticsearch.search.aggs.pipeline;

import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.MultiBucketsAggregation;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalPercentiles;
import com.naqqa.elasticsearch.search.aggs.metrics.PercentilesMethod;
import com.naqqa.elasticsearch.search.aggs.support.sketch.TDigest;

public final class PercentilesBucketAggregator {

    private PercentilesBucketAggregator() {
    }

    public static InternalAggregation apply(MultiBucketsAggregation parent, String bucketsPath, String outputName, double[] percents) {
        TDigest digest = new TDigest(TDigest.DEFAULT_COMPRESSION);
        for (MultiBucketsAggregation.Bucket b : parent.getBuckets()) {
            double v = PipelineUtil.resolveValue(b, bucketsPath);
            if (!Double.isNaN(v)) {
                digest.add(v);
            }
        }
        return new InternalPercentiles(outputName, PercentilesMethod.TDIGEST, percents, digest, null, null);
    }
}
