package com.naqqa.elasticsearch.search.aggs.bucket.sampler;

import com.naqqa.elasticsearch.search.aggs.AggParseContext;
import com.naqqa.elasticsearch.search.aggs.Aggregator;
import com.naqqa.elasticsearch.search.aggs.BucketsAggregator;
import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.InternalAggregations;
import com.naqqa.elasticsearch.search.aggs.bucket.filter.InternalSingleBucketAggregation;
import com.naqqa.elasticsearch.search.aggs.support.MultiBucketConsumer;
import com.naqqa.elasticsearch.search.aggs.support.ParamsHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntUnaryOperator;

public final class DiversifiedSamplerAggregator extends BucketsAggregator {

    private final int shardSize;
    private final int maxDocsPerValue;
    private final IntUnaryOperator diversityKeyOf;
    private final Map<Long, Map<Long, List<Integer>>> perOwningDocsByKey = new HashMap<>();
    private final Set<Long> finalized = new HashSet<>();

    public DiversifiedSamplerAggregator(String name, Aggregator[] subAggregators, MultiBucketConsumer bucketConsumer, int shardSize,
                                         int maxDocsPerValue, IntUnaryOperator diversityKeyOf) {
        super(name, subAggregators, bucketConsumer);
        this.shardSize = shardSize;
        this.maxDocsPerValue = maxDocsPerValue;
        this.diversityKeyOf = diversityKeyOf;
    }

    @SuppressWarnings("unchecked")
    public static Aggregator parse(AggParseContext ctx) {
        int shardSize = ParamsHelper.getInt(ctx.params(), "shard_size", 100);
        int maxDocsPerValue = ParamsHelper.getInt(ctx.params(), "max_docs_per_value", 1);
        IntUnaryOperator diversityKeyOf = (IntUnaryOperator) ctx.params().get("diversity_key_of");
        return new DiversifiedSamplerAggregator(ctx.name(), ctx.subAggregators(), ctx.bucketConsumer(), shardSize, maxDocsPerValue, diversityKeyOf);
    }

    @Override
    public void collect(int doc, long bucketOrd) {
        Map<Long, List<Integer>> byKey = perOwningDocsByKey.computeIfAbsent(bucketOrd, k -> new HashMap<>());
        long key = diversityKeyOf != null ? diversityKeyOf.applyAsInt(doc) : 0L;
        List<Integer> docs = byKey.computeIfAbsent(key, k -> new ArrayList<>());
        if (docs.size() < maxDocsPerValue) {
            docs.add(doc);
        }
    }

    @Override
    public InternalAggregation buildAggregation(long owningBucketOrd) {
        if (!finalized.contains(owningBucketOrd)) {
            finalized.add(owningBucketOrd);
            Map<Long, List<Integer>> byKey = perOwningDocsByKey.get(owningBucketOrd);
            if (byKey != null) {
                List<Integer> all = new ArrayList<>();
                for (List<Integer> docs : byKey.values()) {
                    all.addAll(docs);
                }
                int limit = Math.min(all.size(), shardSize);
                for (int i = 0; i < limit; i++) {
                    collectBucket(all.get(i), owningBucketOrd, 0L);
                }
            }
        }
        long bucketOrd = bucketOrds.find(owningBucketOrd, 0L);
        long docCount = bucketDocCount(bucketOrd);
        InternalAggregations subAggs = buildSubAggsForBucket(bucketOrd);
        return new InternalSingleBucketAggregation(name, "diversified_sampler", docCount, subAggs, null);
    }
}
