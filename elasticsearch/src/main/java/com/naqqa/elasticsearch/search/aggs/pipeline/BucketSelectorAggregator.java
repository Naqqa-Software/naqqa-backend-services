package com.naqqa.elasticsearch.search.aggs.pipeline;

import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.MultiBucketsAggregation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public final class BucketSelectorAggregator {

    private BucketSelectorAggregator() {
    }

    public static InternalAggregation apply(MultiBucketsAggregation parent, Map<String, String> bucketsPaths, Predicate<Map<String, Double>> predicate) {
        List<? extends MultiBucketsAggregation.Bucket> buckets = parent.getBuckets();
        List<MultiBucketsAggregation.Bucket> kept = new ArrayList<>();
        for (MultiBucketsAggregation.Bucket b : buckets) {
            Map<String, Double> vars = new LinkedHashMap<>();
            boolean hasGap = false;
            for (Map.Entry<String, String> e : bucketsPaths.entrySet()) {
                double v = PipelineUtil.resolveValue(b, e.getValue());
                if (Double.isNaN(v)) {
                    hasGap = true;
                    break;
                }
                vars.put(e.getKey(), v);
            }
            if (hasGap) {
                continue;
            }
            if (predicate.test(vars)) {
                kept.add(b);
            }
        }
        return parent.withBuckets(kept);
    }
}
