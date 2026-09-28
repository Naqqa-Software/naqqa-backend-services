package com.naqqa.elasticsearch.search.aggs.bucket.terms;

import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.InternalAggregations;
import com.naqqa.elasticsearch.search.aggs.MultiBucketsAggregation;
import com.naqqa.elasticsearch.search.aggs.ReduceContext;
import com.naqqa.elasticsearch.search.aggs.bucket.BucketReduceUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InternalRareTerms extends InternalAggregation implements MultiBucketsAggregation {

    private final List<TermsBucket> buckets;
    private final long maxDocCount;

    public InternalRareTerms(String name, List<TermsBucket> buckets, long maxDocCount, Map<String, Object> metadata) {
        super(name, metadata);
        this.buckets = buckets;
        this.maxDocCount = maxDocCount;
    }

    @Override
    public List<? extends Bucket> getBuckets() {
        return buckets;
    }

    @Override
    public InternalAggregation withBuckets(List<? extends Bucket> newBuckets) {
        List<TermsBucket> cast = new ArrayList<>(newBuckets.size());
        for (Bucket b : newBuckets) {
            cast.add((TermsBucket) b);
        }
        return new InternalRareTerms(getName(), cast, maxDocCount, getMetadata());
    }

    public long maxDocCountValue() {
        return maxDocCount;
    }

    @Override
    public String getType() {
        return "rare_terms";
    }

    @Override
    public InternalAggregation reduce(List<InternalAggregation> aggregations, ReduceContext context) {
        List<List<TermsBucket>> perShard = new ArrayList<>();
        for (InternalAggregation a : aggregations) {
            perShard.add(((InternalRareTerms) a).buckets);
        }
        LinkedHashMap<Object, List<TermsBucket>> grouped = com.naqqa.elasticsearch.search.aggs.bucket.BucketReduceUtil.groupByKey(perShard);
        List<TermsBucket> reduced = new ArrayList<>();
        for (List<TermsBucket> members : grouped.values()) {
            long docCount = BucketReduceUtil.sumDocCount(members);
            if (context.isFinalReduce() && docCount > maxDocCount) {
                continue;
            }
            InternalAggregations subAggs = BucketReduceUtil.reduceSubAggs(members, context);
            reduced.add(new TermsBucket(members.get(0).getKey(), docCount, 0, subAggs));
        }
        reduced.sort((a, b) -> Long.compare(a.getDocCount(), b.getDocCount()));
        if (context.isFinalReduce()) {
            context.consumeBucketsAndMaybeBreak(reduced.size());
        }
        return new InternalRareTerms(getName(), reduced, maxDocCount, getMetadata());
    }

    @Override
    public Map<String, Object> toMap() {
        List<Object> list = new ArrayList<>();
        for (TermsBucket b : buckets) {
            list.add(b.toMap(false));
        }
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("buckets", list);
        return map;
    }
}
