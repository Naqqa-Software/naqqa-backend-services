package com.naqqa.elasticsearch.search.execution;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

public final class LRUCache<K, V> {

    private final int maxSize;
    private final LinkedHashMap<K, V> map;

    public LRUCache(int maxSize) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be > 0, got " + maxSize);
        }
        this.maxSize = maxSize;
        this.map = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > LRUCache.this.maxSize;
            }
        };
    }

    public synchronized V get(K key) {
        return map.get(key);
    }

    public synchronized void put(K key, V value) {
        map.put(key, value);
    }

    public synchronized V computeIfAbsent(K key, Supplier<V> supplier) {
        V existing = map.get(key);
        if (existing != null) {
            return existing;
        }
        V computed = supplier.get();
        map.put(key, computed);
        return computed;
    }

    public synchronized void invalidate(K key) {
        map.remove(key);
    }

    public synchronized int size() {
        return map.size();
    }

    public synchronized void clear() {
        map.clear();
    }
}
