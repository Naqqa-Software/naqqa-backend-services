package com.naqqa.elasticsearch.search.execution;

import com.naqqa.elasticsearch.search.query.Query;

import java.util.function.Supplier;

public final class RequestCache<V> {

    private final LRUCache<Key, V> cache;

    public RequestCache(int maxSize) {
        this.cache = new LRUCache<>(maxSize);
    }

    public V computeIfAbsent(Object shardKey, Query query, Supplier<V> compute) {
        return cache.computeIfAbsent(new Key(shardKey, query), compute);
    }

    public int size() {
        return cache.size();
    }

    public void clear() {
        cache.clear();
    }

    private record Key(Object shardKey, Query query) {
    }
}
