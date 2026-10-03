package com.naqqa.analytics.collect;

import java.time.Duration;
import java.util.List;

public interface KeyValueStore {

    long increment(String key, long delta, Duration ttl);

    String getOrSet(String key, String value, Duration ttl);

    void hllAdd(String key, Duration ttl, String... values);

    long hllCount(String key);

    long push(String key, List<String> values, long maxLength);

    List<String> pop(String key, int count);

    long length(String key);

    boolean distributed();
}
