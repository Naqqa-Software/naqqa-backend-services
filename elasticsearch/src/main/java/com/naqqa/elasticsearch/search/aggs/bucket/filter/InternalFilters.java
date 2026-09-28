package com.naqqa.elasticsearch.search.aggs.bucket.filter;

import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.InternalAggregations;
import com.naqqa.elasticsearch.search.aggs.MultiBucketsAggregation;
import com.naqqa.elasticsearch.search.aggs.ReduceContext;
import com.naqqa.elasticsearch.search.aggs.bucket.BucketReduceUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InternalFilters extends InternalAggregation implements MultiBucketsAggregation {

    private final List<FiltersBucket> buckets;
    private final boolean keyed;

    public InternalFilters(String name, List<FiltersBucket> buckets, boolean keyed, Map<String, Object> metadata) {
        super(name, metadata);
        this.buckets = buckets;
        this.keyed = keyed;
    }

    @Override
    public List<? extends Bucket> getBuckets() {
        return buckets;
    }

    @Override
    public InternalAggregation withBuckets(List<? extends Bucket> newBuckets) {
        List<FiltersBucket> cast = new ArrayList<>(newBuckets.size());
        for (Bucket b : newBuckets) {
            cast.add((FiltersBucket) b);
        }
        return new InternalFilters(getName(), cast, keyed, getMetadata());
    }

    public boolean isKeyedValue() {
        return keyed;
    }

    @Override
    public String getType() {
        return "filters";
    }

    @Override
    public InternalAggregation reduce(List<InternalAggregation> aggregations, ReduceContext context) {
        List<FiltersBucket> merged = new ArrayList<>();
        int count = buckets.size();
        for (int i = 0; i < count; i++) {
            List<FiltersBucket> members = new ArrayList<>();
            for (InternalAggregation a : aggregations) {
                members.add(((InternalFilters) a).buckets.get(i));
            }
            long docCount = BucketReduceUtil.sumDocCount(members);
            InternalAggregations subAggs = BucketReduceUtil.reduceSubAggs(members, context);
            merged.add(new FiltersBucket(members.get(0).getKeyAsString(), docCount, subAggs));
        }
        if (context.isFinalReduce()) {
            context.consumeBucketsAndMaybeBreak(merged.size());
        }
        return new InternalFilters(getName(), merged, keyed, getMetadata());
    }

    @Override
    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        if (keyed) {
            LinkedHashMap<String, Object> byKey = new LinkedHashMap<>();
            for (FiltersBucket b : buckets) {
                byKey.put(b.getKeyAsString(), b.toMap());
            }
            map.put("buckets", byKey);
        } else {
            List<Object> list = new ArrayList<>();
            for (FiltersBucket b : buckets) {
                list.add(b.toMap());
            }
            map.put("buckets", list);
        }
        return map;
    }
}
