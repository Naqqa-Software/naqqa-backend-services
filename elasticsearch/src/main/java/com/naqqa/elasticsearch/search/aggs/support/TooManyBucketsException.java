package com.naqqa.elasticsearch.search.aggs.support;

public final class TooManyBucketsException extends RuntimeException {

    private final int maxBuckets;

    public TooManyBucketsException(String message, int maxBuckets) {
        super(message);
        this.maxBuckets = maxBuckets;
    }

    public int getMaxBuckets() {
        return maxBuckets;
    }
}
