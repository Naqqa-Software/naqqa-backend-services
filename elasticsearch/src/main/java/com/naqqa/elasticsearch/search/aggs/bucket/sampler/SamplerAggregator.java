package com.naqqa.elasticsearch.search.aggs.bucket.sampler;

import com.naqqa.elasticsearch.search.aggs.AggParseContext;
import com.naqqa.elasticsearch.search.aggs.Aggregator;
import com.naqqa.elasticsearch.search.aggs.BucketsAggregator;
import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.InternalAggregations;
import com.naqqa.elasticsearch.search.aggs.bucket.filter.InternalSingleBucketAggregation;
import com.naqqa.elasticsearch.search.aggs.support.MultiBucketConsumer;
import com.naqqa.elasticsearch.search.aggs.support.ParamsHelper;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

public class SamplerAggregator extends BucketsAggregator {

    protected final int shardSize;
    protected final SplittableRandom random;
    private final Map<Long, int[]> reservoirs = new HashMap<>();
    private final Map<Long, Integer> seenCounts = new HashMap<>();
    private final Set<Long> finalized = new HashSet<>();

    public SamplerAggregator(String name, Aggregator[] subAggregators, MultiBucketConsumer bucketConsumer, int shardSize, long seed) {
        super(name, subAggregators, bucketConsumer);
        this.shardSize = shardSize;
        this.random = new SplittableRandom(seed);
    }

    public static Aggregator parse(AggParseContext ctx) {
        int shardSize = ParamsHelper.getInt(ctx.params(), "shard_size", 100);
        long seed = ParamsHelper.getLong(ctx.params(), "seed", 0L);
        return new SamplerAggregator(ctx.name(), ctx.subAggregators(), ctx.bucketConsumer(), shardSize, seed);
    }

    @Override
    public void collect(int doc, long bucketOrd) {
        int[] reservoir = reservoirs.computeIfAbsent(bucketOrd, k -> new int[shardSize]);
        int seen = seenCounts.getOrDefault(bucketOrd, 0);
        if (seen < shardSize) {
            reservoir[seen] = doc;
        } else {
            long j = random.nextLong(seen + 1L);
            if (j < shardSize) {
                reservoir[(int) j] = doc;
            }
        }
        seenCounts.put(bucketOrd, seen + 1);
    }

    @Override
    public InternalAggregation buildAggregation(long owningBucketOrd) {
        if (!finalized.contains(owningBucketOrd)) {
            finalized.add(owningBucketOrd);
            int[] reservoir = reservoirs.get(owningBucketOrd);
            int seen = seenCounts.getOrDefault(owningBucketOrd, 0);
            if (reservoir != null) {
                int n = Math.min(seen, shardSize);
                for (int i = 0; i < n; i++) {
                    collectBucket(reservoir[i], owningBucketOrd, 0L);
                }
            }
        }
        long bucketOrd = bucketOrds.find(owningBucketOrd, 0L);
        long docCount = bucketDocCount(bucketOrd);
        InternalAggregations subAggs = buildSubAggsForBucket(bucketOrd);
        return new InternalSingleBucketAggregation(name, "sampler", docCount, subAggs, null);
    }
}
