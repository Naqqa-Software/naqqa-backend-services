package com.naqqa.elasticsearch.search.aggs;

import com.naqqa.elasticsearch.search.aggs.support.MultiBucketConsumer;

public final class ReduceContext {

    private final boolean finalReduce;
    private final MultiBucketConsumer bucketConsumer;

    public ReduceContext(boolean finalReduce, MultiBucketConsumer bucketConsumer) {
        this.finalReduce = finalReduce;
        this.bucketConsumer = bucketConsumer;
    }

    public static ReduceContext forFinalReduction() {
        return new ReduceContext(true, new MultiBucketConsumer(MultiBucketConsumer.DEFAULT_MAX_BUCKETS));
    }

    public static ReduceContext forFinalReduction(MultiBucketConsumer consumer) {
        return new ReduceContext(true, consumer);
    }

    public static ReduceContext forPartialReduction() {
        return new ReduceContext(false, null);
    }

    public boolean isFinalReduce() {
        return finalReduce;
    }

    public MultiBucketConsumer bucketConsumer() {
        return bucketConsumer;
    }

    public void consumeBucketsAndMaybeBreak(int count) {
        if (bucketConsumer != null) {
            bucketConsumer.accept(count);
        }
    }
}
