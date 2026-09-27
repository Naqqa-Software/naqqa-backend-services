package com.naqqa.elasticsearch.search.aggs.pipeline;

import com.naqqa.elasticsearch.search.aggs.BucketValueExtractor;
import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.InternalAggregations;
import com.naqqa.elasticsearch.search.aggs.MultiBucketsAggregation;

import java.util.ArrayList;
import java.util.List;

public final class PipelineUtil {

    private PipelineUtil() {
    }

    public static double resolveValue(MultiBucketsAggregation.Bucket bucket, String bucketsPath) {
        try {
            return BucketValueExtractor.extract(bucket.getAggregations(), bucket.getDocCount(), bucketsPath);
        } catch (IllegalArgumentException e) {
            return Double.NaN;
        }
    }

    public static MultiBucketsAggregation.Bucket withExtraMetric(MultiBucketsAggregation.Bucket bucket, InternalAggregation extra) {
        List<InternalAggregation> combined = new ArrayList<>(bucket.getAggregations().aggregations());
        combined.add(extra);
        return bucket.withAggregations(new InternalAggregations(combined));
    }

    @SuppressWarnings("unchecked")
    public static InternalAggregation applyPerBucket(MultiBucketsAggregation parent, BucketValueFunction fn) {
        List<? extends MultiBucketsAggregation.Bucket> buckets = parent.getBuckets();
        List<MultiBucketsAggregation.Bucket> updated = new ArrayList<>(buckets.size());
        for (int i = 0; i < buckets.size(); i++) {
            updated.add(fn.apply(buckets, i));
        }
        return parent.withBuckets(updated);
    }

    @FunctionalInterface
    public interface BucketValueFunction {
        MultiBucketsAggregation.Bucket apply(List<? extends MultiBucketsAggregation.Bucket> buckets, int index);
    }
}
