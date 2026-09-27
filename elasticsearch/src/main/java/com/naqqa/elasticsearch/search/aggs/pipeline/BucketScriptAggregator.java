package com.naqqa.elasticsearch.search.aggs.pipeline;

import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.MultiBucketsAggregation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class BucketScriptAggregator {

    private BucketScriptAggregator() {
    }

    public static InternalAggregation apply(MultiBucketsAggregation parent, Map<String, String> bucketsPaths, String outputName,
                                              Function<Map<String, Double>, Double> script) {
        List<? extends MultiBucketsAggregation.Bucket> buckets = parent.getBuckets();
        List<MultiBucketsAggregation.Bucket> updated = new ArrayList<>(buckets.size());
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
                updated.add(b);
            } else {
                Double result = script.apply(vars);
                updated.add(PipelineUtil.withExtraMetric(b, new InternalSimpleValue(outputName, result == null ? Double.NaN : result, null)));
            }
        }
        return parent.withBuckets(updated);
    }
}
