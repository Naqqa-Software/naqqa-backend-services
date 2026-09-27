package com.naqqa.elasticsearch.search.aggs.pipeline;

import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.MultiBucketsAggregation;

import java.util.ArrayList;
import java.util.List;

public final class CumulativeSumAggregator {

    private CumulativeSumAggregator() {
    }

    public static InternalAggregation apply(MultiBucketsAggregation parent, String bucketsPath, String outputName) {
        List<? extends MultiBucketsAggregation.Bucket> buckets = parent.getBuckets();
        List<MultiBucketsAggregation.Bucket> updated = new ArrayList<>(buckets.size());
        double running = 0;
        for (MultiBucketsAggregation.Bucket b : buckets) {
            double v = PipelineUtil.resolveValue(b, bucketsPath);
            if (!Double.isNaN(v)) {
                running += v;
            }
            updated.add(PipelineUtil.withExtraMetric(b, new InternalSimpleValue(outputName, running, null)));
        }
        return parent.withBuckets(updated);
    }
}
