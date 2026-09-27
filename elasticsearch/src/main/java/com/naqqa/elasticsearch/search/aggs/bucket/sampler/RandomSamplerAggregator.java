package com.naqqa.elasticsearch.search.aggs.bucket.sampler;

import com.naqqa.elasticsearch.search.aggs.AggParseContext;
import com.naqqa.elasticsearch.search.aggs.Aggregator;
import com.naqqa.elasticsearch.search.aggs.BucketsAggregator;
import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.InternalAggregations;
import com.naqqa.elasticsearch.search.aggs.bucket.filter.InternalSingleBucketAggregation;
import com.naqqa.elasticsearch.search.aggs.support.MultiBucketConsumer;
import com.naqqa.elasticsearch.search.aggs.support.ParamsHelper;

import java.util.SplittableRandom;

public final class RandomSamplerAggregator extends BucketsAggregator {

    private final double probability;
    private final SplittableRandom random;

    public RandomSamplerAggregator(String name, Aggregator[] subAggregators, MultiBucketConsumer bucketConsumer, double probability, long seed) {
        super(name, subAggregators, bucketConsumer);
        this.probability = probability;
        this.random = new SplittableRandom(seed);
    }

    public static Aggregator parse(AggParseContext ctx) {
        double probability = ParamsHelper.getDouble(ctx.params(), "probability", 0.1);
        long seed = ParamsHelper.getLong(ctx.params(), "seed", 0L);
        return new RandomSamplerAggregator(ctx.name(), ctx.subAggregators(), ctx.bucketConsumer(), probability, seed);
    }

    @Override
    public void collect(int doc, long bucketOrd) {
        if (random.nextDouble() < probability) {
            collectBucket(doc, bucketOrd, 0L);
        }
    }

    @Override
    public InternalAggregation buildAggregation(long owningBucketOrd) {
        long bucketOrd = bucketOrds.find(owningBucketOrd, 0L);
        long docCount = bucketDocCount(bucketOrd);
        InternalAggregations subAggs = buildSubAggsForBucket(bucketOrd);
        return new InternalSingleBucketAggregation(name, "random_sampler", docCount, subAggs, null);
    }
}
