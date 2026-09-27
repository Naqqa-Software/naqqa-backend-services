package com.naqqa.elasticsearch.search.aggs.bucket.terms;

import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.InternalAggregations;
import com.naqqa.elasticsearch.search.aggs.MultiBucketsAggregation;
import com.naqqa.elasticsearch.search.aggs.ReduceContext;
import com.naqqa.elasticsearch.search.aggs.bucket.BucketReduceUtil;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class InternalTerms extends InternalAggregation implements MultiBucketsAggregation {

    private final List<TermsBucket> buckets;
    private final long sumOtherDocCount;
    private final int requiredSize;
    private final long minDocCount;
    private final TermsOrder order;
    private final boolean showTermDocCountError;

    public InternalTerms(String name, List<TermsBucket> buckets, long sumOtherDocCount, int requiredSize, long minDocCount,
                          TermsOrder order, boolean showTermDocCountError, Map<String, Object> metadata) {
        super(name, metadata);
        this.buckets = buckets;
        this.sumOtherDocCount = sumOtherDocCount;
        this.requiredSize = requiredSize;
        this.minDocCount = minDocCount;
        this.order = order;
        this.showTermDocCountError = showTermDocCountError;
    }

    @Override
    public List<? extends Bucket> getBuckets() {
        return buckets;
    }

    public long getSumOtherDocCount() {
        return sumOtherDocCount;
    }

    public int getRequiredSize() {
        return requiredSize;
    }

    public long getMinDocCount() {
        return minDocCount;
    }

    public TermsOrder getOrder() {
        return order;
    }

    public boolean isShowTermDocCountError() {
        return showTermDocCountError;
    }

    @Override
    @SuppressWarnings("unchecked")
    public InternalAggregation withBuckets(List<? extends Bucket> newBuckets) {
        List<TermsBucket> cast = new ArrayList<>(newBuckets.size());
        for (Bucket b : newBuckets) {
            cast.add((TermsBucket) b);
        }
        return new InternalTerms(getName(), cast, sumOtherDocCount, requiredSize, minDocCount, order, showTermDocCountError, getMetadata());
    }

    @Override
    public String getType() {
        return "terms";
    }

    @Override
    public InternalAggregation reduce(List<InternalAggregation> aggregations, ReduceContext context) {
        List<InternalTerms> shards = new ArrayList<>(aggregations.size());
        for (InternalAggregation a : aggregations) {
            shards.add((InternalTerms) a);
        }
        long[] shardMinDocCount = new long[shards.size()];
        List<Set<Object>> keysPerShard = new ArrayList<>();
        for (int i = 0; i < shards.size(); i++) {
            InternalTerms shard = shards.get(i);
            long min = Long.MAX_VALUE;
            Set<Object> keys = new HashSet<>();
            for (TermsBucket b : shard.buckets) {
                min = Math.min(min, b.getDocCount());
                keys.add(b.getKey());
            }
            shardMinDocCount[i] = shard.buckets.isEmpty() ? 0 : min;
            keysPerShard.add(keys);
        }
        LinkedHashMap<Object, List<TermsBucket>> grouped = new LinkedHashMap<>();
        for (InternalTerms shard : shards) {
            for (TermsBucket b : shard.buckets) {
                grouped.computeIfAbsent(b.getKey(), k -> new ArrayList<>()).add(b);
            }
        }
        long sumOther = 0;
        for (InternalTerms shard : shards) {
            sumOther += shard.sumOtherDocCount;
        }
        List<TermsBucket> reduced = new ArrayList<>(grouped.size());
        for (Map.Entry<Object, List<TermsBucket>> e : grouped.entrySet()) {
            List<TermsBucket> members = e.getValue();
            long docCount = BucketReduceUtil.sumDocCount(members);
            InternalAggregations subAggs = BucketReduceUtil.reduceSubAggs(members, context);
            long error = 0;
            for (TermsBucket m : members) {
                error += m.getDocCountError();
            }
            for (int i = 0; i < shards.size(); i++) {
                if (!keysPerShard.get(i).contains(e.getKey())) {
                    error += shardMinDocCount[i];
                }
            }
            reduced.add(new TermsBucket(e.getKey(), docCount, error, subAggs));
        }
        reduced.sort(order.comparator());
        if (context.isFinalReduce()) {
            List<TermsBucket> kept = new ArrayList<>();
            for (TermsBucket b : reduced) {
                if (b.getDocCount() < minDocCount) {
                    continue;
                }
                if (kept.size() < requiredSize) {
                    kept.add(b);
                } else {
                    sumOther += b.getDocCount();
                }
            }
            context.consumeBucketsAndMaybeBreak(kept.size());
            return new InternalTerms(getName(), kept, sumOther, requiredSize, minDocCount, order, showTermDocCountError, getMetadata());
        }
        return new InternalTerms(getName(), reduced, sumOther, requiredSize, minDocCount, order, showTermDocCountError, getMetadata());
    }

    @Override
    public Map<String, Object> toMap() {
        long maxError = 0;
        for (TermsBucket b : buckets) {
            maxError = Math.max(maxError, b.getDocCountError());
        }
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("doc_count_error_upper_bound", maxError);
        map.put("sum_other_doc_count", sumOtherDocCount);
        List<Object> list = new ArrayList<>();
        for (TermsBucket b : buckets) {
            list.add(b.toMap(showTermDocCountError));
        }
        map.put("buckets", list);
        return map;
    }
}
