package com.naqqa.elasticsearch.search.aggs.support;

import com.naqqa.elasticsearch.common.breaker.CircuitBreaker;

public final class MultiBucketConsumer {

    public static final int DEFAULT_MAX_BUCKETS = 65536;

    private final int limit;
    private final CircuitBreaker breaker;
    private int count;

    public MultiBucketConsumer(int limit) {
        this(limit, null);
    }

    public MultiBucketConsumer(int limit, CircuitBreaker breaker) {
        this.limit = limit;
        this.breaker = breaker;
    }

    public void accept(int newBuckets) {
        count += newBuckets;
        if (count > limit) {
            throw new TooManyBucketsException(
                "Trying to create too many buckets. Must be less than or equal to: [" + limit
                    + "] but was [" + count + "]. This limit can be set by changing the [search.max_buckets] setting.",
                limit);
        }
        if (breaker != null) {
            breaker.addEstimateBytesAndMaybeBreak(32L * newBuckets, "aggregation_buckets");
        }
    }

    public int getCount() {
        return count;
    }

    public int getLimit() {
        return limit;
    }
}
