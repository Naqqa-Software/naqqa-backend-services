package com.naqqa.elasticsearch.search.aggs.bucket.composite;

import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.InternalAggregations;
import com.naqqa.elasticsearch.search.aggs.MultiBucketsAggregation;
import com.naqqa.elasticsearch.search.aggs.ReduceContext;
import com.naqqa.elasticsearch.search.aggs.bucket.BucketReduceUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InternalComposite extends InternalAggregation implements MultiBucketsAggregation {

    private final List<CompositeBucket> buckets;
    private final int size;
    private final List<Boolean> ascending;

    public InternalComposite(String name, List<CompositeBucket> buckets, int size, List<Boolean> ascending, Map<String, Object> metadata) {
        super(name, metadata);
        this.buckets = buckets;
        this.size = size;
        this.ascending = ascending;
    }

    @Override
    public List<? extends Bucket> getBuckets() {
        return buckets;
    }

    @Override
    public InternalAggregation withBuckets(List<? extends Bucket> newBuckets) {
        List<CompositeBucket> cast = new ArrayList<>(newBuckets.size());
        for (Bucket b : newBuckets) {
            cast.add((CompositeBucket) b);
        }
        return new InternalComposite(getName(), cast, size, ascending, getMetadata());
    }

    @Override
    public String getType() {
        return "composite";
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Comparator<CompositeBucket> keyComparator() {
        return (a, b) -> {
            List<Object> ka = (List<Object>) a.getKey();
            List<Object> kb = (List<Object>) b.getKey();
            for (int i = 0; i < ka.size(); i++) {
                int cmp = ((Comparable) ka.get(i)).compareTo(kb.get(i));
                if (cmp != 0) {
                    return ascending.get(i) ? cmp : -cmp;
                }
            }
            return 0;
        };
    }

    @Override
    public InternalAggregation reduce(List<InternalAggregation> aggregations, ReduceContext context) {
        List<List<CompositeBucket>> perShard = new ArrayList<>();
        for (InternalAggregation a : aggregations) {
            perShard.add(((InternalComposite) a).buckets);
        }
        LinkedHashMap<Object, List<CompositeBucket>> grouped = BucketReduceUtil.groupByKey(perShard);
        List<CompositeBucket> reduced = new ArrayList<>();
        for (List<CompositeBucket> members : grouped.values()) {
            long docCount = BucketReduceUtil.sumDocCount(members);
            InternalAggregations subAggs = BucketReduceUtil.reduceSubAggs(members, context);
            CompositeBucket first = members.get(0);
            reduced.add(new CompositeBucket(first.keyAsMap().keySet().stream().toList(), (List<Object>) first.getKey(), docCount, subAggs));
        }
        reduced.sort(keyComparator());
        if (context.isFinalReduce() && reduced.size() > size) {
            reduced = new ArrayList<>(reduced.subList(0, size));
        }
        if (context.isFinalReduce()) {
            context.consumeBucketsAndMaybeBreak(reduced.size());
        }
        return new InternalComposite(getName(), reduced, size, ascending, getMetadata());
    }

    public Map<String, Object> afterKey() {
        if (buckets.isEmpty()) {
            return null;
        }
        return buckets.get(buckets.size() - 1).keyAsMap();
    }

    @Override
    public Map<String, Object> toMap() {
        List<Object> list = new ArrayList<>();
        for (CompositeBucket b : buckets) {
            list.add(b.toMap());
        }
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("after_key", afterKey());
        map.put("buckets", list);
        return map;
    }
}
