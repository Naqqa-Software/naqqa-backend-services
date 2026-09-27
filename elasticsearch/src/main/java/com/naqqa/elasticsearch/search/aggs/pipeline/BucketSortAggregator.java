package com.naqqa.elasticsearch.search.aggs.pipeline;

import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.MultiBucketsAggregation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class BucketSortAggregator {

    public record SortSpec(String path, boolean ascending) {
    }

    private BucketSortAggregator() {
    }

    public static InternalAggregation apply(MultiBucketsAggregation parent, List<SortSpec> sorts, int from, Integer size) {
        List<MultiBucketsAggregation.Bucket> buckets = new ArrayList<>(parent.getBuckets());
        if (!sorts.isEmpty()) {
            Comparator<MultiBucketsAggregation.Bucket> comparator = null;
            for (SortSpec s : sorts) {
                Comparator<MultiBucketsAggregation.Bucket> cmp = Comparator.comparingDouble(b -> PipelineUtil.resolveValue(b, s.path()));
                if (!s.ascending()) {
                    cmp = cmp.reversed();
                }
                comparator = comparator == null ? cmp : comparator.thenComparing(cmp);
            }
            buckets.sort(comparator);
        }
        int fromIdx = Math.min(from, buckets.size());
        int toIdx = size == null ? buckets.size() : Math.min(buckets.size(), fromIdx + size);
        List<MultiBucketsAggregation.Bucket> sliced = new ArrayList<>(buckets.subList(fromIdx, toIdx));
        return parent.withBuckets(sliced);
    }
}
