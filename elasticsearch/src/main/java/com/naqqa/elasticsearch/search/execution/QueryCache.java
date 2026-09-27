package com.naqqa.elasticsearch.search.execution;

import com.naqqa.elasticsearch.common.util.FixedBitSet;
import com.naqqa.elasticsearch.search.query.Query;

import java.util.function.Supplier;

public final class QueryCache {

    private final LRUCache<Key, FixedBitSet> cache;

    public QueryCache(int maxSize) {
        this.cache = new LRUCache<>(maxSize);
    }

    public FixedBitSet computeIfAbsent(Object segmentKey, Query query, Supplier<FixedBitSet> compute) {
        return cache.computeIfAbsent(new Key(segmentKey, query), compute);
    }

    public void invalidateSegment(Object segmentKey) {
        cache.clear();
    }

    public int size() {
        return cache.size();
    }

    public void clear() {
        cache.clear();
    }

    private record Key(Object segmentKey, Query query) {
    }
}
