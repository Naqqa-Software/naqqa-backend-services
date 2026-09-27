package com.naqqa.elasticsearch.search.aggs.bucket.range;

import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.InternalAggregations;
import com.naqqa.elasticsearch.search.aggs.MultiBucketsAggregation;
import com.naqqa.elasticsearch.search.aggs.ReduceContext;
import com.naqqa.elasticsearch.search.aggs.bucket.BucketReduceUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InternalRange extends InternalAggregation implements MultiBucketsAggregation {

    private final List<RangeBucket> buckets;

    public InternalRange(String name, List<RangeBucket> buckets, Map<String, Object> metadata) {
        super(name, metadata);
        this.buckets = buckets;
    }

    @Override
    public List<? extends Bucket> getBuckets() {
        return buckets;
    }

    @Override
    public InternalAggregation withBuckets(List<? extends Bucket> newBuckets) {
        List<RangeBucket> cast = new ArrayList<>(newBuckets.size());
        for (Bucket b : newBuckets) {
            cast.add((RangeBucket) b);
        }
        return new InternalRange(getName(), cast, getMetadata());
    }

    @Override
    public String getType() {
        return "range";
    }

    @Override
    public InternalAggregation reduce(List<InternalAggregation> aggregations, ReduceContext context) {
        List<RangeBucket> merged = new ArrayList<>();
        int rangeCount = buckets.size();
        for (int i = 0; i < rangeCount; i++) {
            List<RangeBucket> members = new ArrayList<>();
            for (InternalAggregation a : aggregations) {
                members.add(((InternalRange) a).buckets.get(i));
            }
            long docCount = BucketReduceUtil.sumDocCount(members);
            InternalAggregations subAggs = BucketReduceUtil.reduceSubAggs(members, context);
            merged.add(new RangeBucket(members.get(0).spec(), docCount, subAggs));
        }
        if (context.isFinalReduce()) {
            context.consumeBucketsAndMaybeBreak(merged.size());
        }
        return new InternalRange(getName(), merged, getMetadata());
    }

    @Override
    public Map<String, Object> toMap() {
        List<Object> list = new ArrayList<>();
        for (RangeBucket b : buckets) {
            list.add(b.toMap());
        }
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("buckets", list);
        return map;
    }
}
