package com.naqqa.elasticsearch.search.aggs;

import com.naqqa.elasticsearch.search.aggs.support.BucketArrays;
import com.naqqa.elasticsearch.search.aggs.support.BucketOrds;
import com.naqqa.elasticsearch.search.aggs.support.MultiBucketConsumer;

public abstract class BucketsAggregator extends Aggregator {

    protected final BucketOrds bucketOrds = new BucketOrds();
    protected final MultiBucketConsumer bucketConsumer;
    private long[] docCounts = new long[4];

    protected BucketsAggregator(String name, Aggregator[] subAggregators, MultiBucketConsumer bucketConsumer) {
        super(name, subAggregators);
        this.bucketConsumer = bucketConsumer;
    }

    protected long collectBucket(int doc, long owningBucketOrd, long key) {
        long bucketOrd = bucketOrds.add(owningBucketOrd, key);
        boolean isNew = bucketOrd >= 0;
        if (!isNew) {
            bucketOrd = -1L - bucketOrd;
        } else if (bucketConsumer != null) {
            bucketConsumer.accept(1);
        }
        incrementBucketDocCount(bucketOrd, 1);
        collectExistingBucket(doc, bucketOrd);
        return bucketOrd;
    }

    protected void collectExistingBucket(int doc, long bucketOrd) {
        for (Aggregator sub : subAggregators) {
            sub.collect(doc, bucketOrd);
        }
    }

    protected void incrementBucketDocCount(long bucketOrd, long inc) {
        docCounts = BucketArrays.grow(docCounts, (int) bucketOrd + 1);
        docCounts[(int) bucketOrd] += inc;
    }

    protected long bucketDocCount(long bucketOrd) {
        return bucketOrd >= 0 && bucketOrd < docCounts.length ? docCounts[(int) bucketOrd] : 0;
    }

    protected InternalAggregations buildSubAggsForBucket(long bucketOrd) {
        InternalAggregation[] result = new InternalAggregation[subAggregators.length];
        for (int i = 0; i < subAggregators.length; i++) {
            result[i] = subAggregators[i].buildAggregation(bucketOrd);
        }
        return InternalAggregations.from(result);
    }
}
