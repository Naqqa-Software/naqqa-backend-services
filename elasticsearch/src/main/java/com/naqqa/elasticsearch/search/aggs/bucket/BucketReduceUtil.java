package com.naqqa.elasticsearch.search.aggs.bucket;

import com.naqqa.elasticsearch.search.aggs.InternalAggregations;
import com.naqqa.elasticsearch.search.aggs.MultiBucketsAggregation;
import com.naqqa.elasticsearch.search.aggs.ReduceContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

public final class BucketReduceUtil {

    private BucketReduceUtil() {
    }

    public static <B extends MultiBucketsAggregation.Bucket> LinkedHashMap<Object, List<B>> groupByKey(List<List<B>> perAggBuckets) {
        LinkedHashMap<Object, List<B>> grouped = new LinkedHashMap<>();
        for (List<B> list : perAggBuckets) {
            for (B b : list) {
                grouped.computeIfAbsent(b.getKey(), k -> new ArrayList<>()).add(b);
            }
        }
        return grouped;
    }

    public static long sumDocCount(List<? extends MultiBucketsAggregation.Bucket> buckets) {
        long sum = 0;
        for (MultiBucketsAggregation.Bucket b : buckets) {
            sum += b.getDocCount();
        }
        return sum;
    }

    public static InternalAggregations reduceSubAggs(List<? extends MultiBucketsAggregation.Bucket> buckets, ReduceContext context) {
        List<InternalAggregations> subs = new ArrayList<>(buckets.size());
        for (MultiBucketsAggregation.Bucket b : buckets) {
            subs.add(b.getAggregations());
        }
        return InternalAggregations.reduceAll(subs, context);
    }
}
