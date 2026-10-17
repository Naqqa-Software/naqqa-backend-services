package com.naqqa.sso.ratelimit;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public final class InMemoryRateLimitStore implements RateLimitStore {
    private final Map<String, BucketState> buckets = new HashMap<>();
    private final Map<String, List<Long>> windows = new HashMap<>();
    private final Map<String, FailureState> failures = new HashMap<>();

    @Override
    public synchronized BucketState bucket(String key) {
        return buckets.get(key);
    }

    @Override
    public synchronized void putBucket(String key, BucketState state) {
        buckets.put(key, state);
    }

    @Override
    public synchronized List<Long> window(String key) {
        return windows.getOrDefault(key, List.of());
    }

    @Override
    public synchronized void putWindow(String key, List<Long> timestamps) {
        windows.put(key, List.copyOf(timestamps));
    }

    @Override
    public synchronized FailureState failures(String key) {
        return failures.get(key);
    }

    @Override
    public synchronized void putFailures(String key, FailureState state) {
        failures.put(key, state);
    }

    @Override
    public synchronized void remove(String key) {
        buckets.remove(key);
        windows.remove(key);
        failures.remove(key);
    }

    @Override
    public synchronized <R> R atomically(Supplier<R> action) {
        return action.get();
    }
}
