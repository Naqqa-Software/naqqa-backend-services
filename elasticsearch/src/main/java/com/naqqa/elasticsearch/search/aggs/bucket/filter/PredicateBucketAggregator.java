package com.naqqa.elasticsearch.search.aggs.bucket.filter;

import com.naqqa.elasticsearch.search.aggs.Aggregator;
import com.naqqa.elasticsearch.search.aggs.BucketsAggregator;
import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.InternalAggregations;
import com.naqqa.elasticsearch.search.aggs.support.MultiBucketConsumer;

import java.util.function.IntPredicate;

public final class PredicateBucketAggregator extends BucketsAggregator {

    private final String type;
    private final IntPredicate predicate;

    public PredicateBucketAggregator(String name, String type, Aggregator[] subAggregators, MultiBucketConsumer bucketConsumer, IntPredicate predicate) {
        super(name, subAggregators, bucketConsumer);
        this.type = type;
        this.predicate = predicate;
    }

    @Override
    public void collect(int doc, long bucketOrd) {
        if (predicate.test(doc)) {
            collectBucket(doc, bucketOrd, 0L);
        }
    }

    @Override
    public InternalAggregation buildAggregation(long owningBucketOrd) {
        long bucketOrd = bucketOrds.find(owningBucketOrd, 0L);
        long docCount = bucketDocCount(bucketOrd);
        InternalAggregations subAggs = buildSubAggsForBucket(bucketOrd);
        return new InternalSingleBucketAggregation(name, type, docCount, subAggs, null);
    }
}
