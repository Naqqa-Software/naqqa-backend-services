package com.naqqa.elasticsearch.search.aggs.bucket.histogram;

import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.InternalAggregations;
import com.naqqa.elasticsearch.search.aggs.MultiBucketsAggregation;
import com.naqqa.elasticsearch.search.aggs.ReduceContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InternalVariableWidthHistogram extends InternalAggregation implements MultiBucketsAggregation {

    private final List<VariableWidthHistogramBucket> buckets;
    private final int targetBuckets;

    public InternalVariableWidthHistogram(String name, List<VariableWidthHistogramBucket> buckets, int targetBuckets, Map<String, Object> metadata) {
        super(name, metadata);
        this.buckets = buckets;
        this.targetBuckets = targetBuckets;
    }

    @Override
    public List<? extends Bucket> getBuckets() {
        return buckets;
    }

    @Override
    public InternalAggregation withBuckets(List<? extends Bucket> newBuckets) {
        List<VariableWidthHistogramBucket> cast = new ArrayList<>(newBuckets.size());
        for (Bucket b : newBuckets) {
            cast.add((VariableWidthHistogramBucket) b);
        }
        return new InternalVariableWidthHistogram(getName(), cast, targetBuckets, getMetadata());
    }

    public int targetBucketsValue() {
        return targetBuckets;
    }

    @Override
    public String getType() {
        return "variable_width_histogram";
    }

    @Override
    public InternalAggregation reduce(List<InternalAggregation> aggregations, ReduceContext context) {
        List<VariableWidthHistogramBucket> all = new ArrayList<>();
        for (InternalAggregation a : aggregations) {
            all.addAll(((InternalVariableWidthHistogram) a).buckets);
        }
        List<VariableWidthHistogramBucket> merged = mergeToTarget(all, targetBuckets, context);
        if (context.isFinalReduce()) {
            context.consumeBucketsAndMaybeBreak(merged.size());
        }
        return new InternalVariableWidthHistogram(getName(), merged, targetBuckets, getMetadata());
    }

    private static List<VariableWidthHistogramBucket> mergeToTarget(List<VariableWidthHistogramBucket> buckets, int target, ReduceContext context) {
        List<VariableWidthHistogramBucket> sorted = new ArrayList<>(buckets);
        sorted.sort(Comparator.comparingDouble(b -> (double) b.getKey()));
        while (sorted.size() > target && sorted.size() > 1) {
            int bestIdx = 0;
            double bestGap = Double.POSITIVE_INFINITY;
            for (int i = 0; i < sorted.size() - 1; i++) {
                double gap = (double) sorted.get(i + 1).getKey() - (double) sorted.get(i).getKey();
                if (gap < bestGap) {
                    bestGap = gap;
                    bestIdx = i;
                }
            }
            VariableWidthHistogramBucket a = sorted.get(bestIdx);
            VariableWidthHistogramBucket b = sorted.get(bestIdx + 1);
            long count = a.getDocCount() + b.getDocCount();
            double newKey = count == 0 ? 0 : (((double) a.getKey()) * a.getDocCount() + ((double) b.getKey()) * b.getDocCount()) / count;
            InternalAggregations mergedAggs = InternalAggregations.reduceAll(List.of(a.getAggregations(), b.getAggregations()), context);
            VariableWidthHistogramBucket merged = new VariableWidthHistogramBucket(newKey, Math.min(a.getMin(), b.getMin()), Math.max(a.getMax(), b.getMax()), count, mergedAggs);
            sorted.set(bestIdx, merged);
            sorted.remove(bestIdx + 1);
        }
        return sorted;
    }

    @Override
    public Map<String, Object> toMap() {
        List<Object> list = new ArrayList<>();
        for (VariableWidthHistogramBucket b : buckets) {
            list.add(b.toMap());
        }
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("buckets", list);
        return map;
    }
}
