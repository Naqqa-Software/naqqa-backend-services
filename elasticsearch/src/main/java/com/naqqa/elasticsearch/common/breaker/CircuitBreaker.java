package com.naqqa.elasticsearch.common.breaker;

public interface CircuitBreaker {

    enum Durability {
        TRANSIENT,
        PERMANENT
    }

    String PARENT = "parent";
    String REQUEST = "request";
    String FIELDDATA = "fielddata";
    String IN_FLIGHT_REQUESTS = "in_flight_requests";
    String ACCOUNTING = "accounting";

    void circuitBreak(String fieldName, long bytesNeeded);

    double addEstimateBytesAndMaybeBreak(long bytes, String label);

    long addWithoutBreaking(long bytes);

    long getUsed();

    long getLimit();

    double getOverhead();

    long getTrippedCount();

    String getName();

    Durability getDurability();
}
