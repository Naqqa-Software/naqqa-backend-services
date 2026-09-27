package com.naqqa.elasticsearch.search.aggs;

public final class AggregationExecutionException extends RuntimeException {

    public AggregationExecutionException(String message) {
        super(message);
    }

    public AggregationExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
