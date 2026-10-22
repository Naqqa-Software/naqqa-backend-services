package com.naqqa.sso.ratelimit;

import java.util.List;
import java.util.function.Supplier;

public interface RateLimitStore {
    BucketState bucket(String key);

    void putBucket(String key, BucketState state);

    List<Long> window(String key);

    void putWindow(String key, List<Long> timestamps);

    FailureState failures(String key);

    void putFailures(String key, FailureState state);

    void remove(String key);

    <R> R atomically(Supplier<R> action);
}
