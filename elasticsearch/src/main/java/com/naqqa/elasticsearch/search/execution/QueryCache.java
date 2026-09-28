package com.naqqa.elasticsearch.search.execution;

import com.naqqa.elasticsearch.common.util.FixedBitSet;
import com.naqqa.elasticsearch.index.engine.segment.SegmentReaderSource;
import com.naqqa.elasticsearch.search.query.Query;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public final class QueryCache {

    public static final long DEFAULT_MAX_BYTES = Math.max(16L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 10);
    public static final int DEFAULT_MIN_USES = 2;
    public static final int DEFAULT_MIN_SEGMENT_DOCS = 10_000;
    private static final int USAGE_HISTORY_LIMIT = 4096;

    public record Stats(long hitCount, long missCount, long cacheSize, long cacheCount, long evictions, long memorySizeInBytes) {
    }

    private record CacheKey(Object segmentKey, Query query) {
    }

    private static final class Entry {
        final FixedBitSet bits;
        final long bytes;

        Entry(FixedBitSet bits, long bytes) {
            this.bits = bits;
            this.bytes = bytes;
        }
    }

    private final long maxBytes;
    private final int minUses;
    private final int minSegmentDocs;

    private final Object lock = new Object();
    private final LinkedHashMap<CacheKey, Entry> cache = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<Object, Set<Query>> bySegment = new ConcurrentHashMap<>();
    private final LinkedHashMap<Query, Integer> useCounts = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Query, Integer> eldest) {
            return size() > USAGE_HISTORY_LIMIT;
        }
    };
    private long usedBytes;
    private long cacheCount;

    private final LongAdder hitCount = new LongAdder();
    private final LongAdder missCount = new LongAdder();
    private final LongAdder evictionCount = new LongAdder();

    public QueryCache() {
        this(DEFAULT_MAX_BYTES, DEFAULT_MIN_USES, DEFAULT_MIN_SEGMENT_DOCS);
    }

    public QueryCache(long maxBytes, int minUses, int minSegmentDocs) {
        this.maxBytes = maxBytes;
        this.minUses = minUses;
        this.minSegmentDocs = minSegmentDocs;
    }

    public static Object segmentKey(LeafReader reader) {
        if (reader instanceof SegmentReaderSource src) {
            return src.segmentReader();
        }
        return reader;
    }

    public FixedBitSet get(Object segmentKey, Query query) {
        synchronized (lock) {
            Entry e = cache.get(new CacheKey(segmentKey, query));
            if (e != null) {
                hitCount.increment();
                return e.bits;
            }
            missCount.increment();
            return null;
        }
    }

    public boolean shouldCache(Query query, int segmentMaxDoc) {
        if (segmentMaxDoc < minSegmentDocs) {
            return false;
        }
        synchronized (lock) {
            int count = useCounts.merge(query, 1, Integer::sum);
            return count >= minUses;
        }
    }

    public void put(Object segmentKey, Query query, FixedBitSet bits) {
        long bytes = bits.ramBytesUsed();
        synchronized (lock) {
            CacheKey key = new CacheKey(segmentKey, query);
            Entry previous = cache.put(key, new Entry(bits, bytes));
            if (previous != null) {
                usedBytes -= previous.bytes;
            } else {
                bySegment.computeIfAbsent(segmentKey, k -> new LinkedHashSet<>()).add(query);
            }
            usedBytes += bytes;
            cacheCount++;
            evictIfNeeded();
        }
    }

    private void evictIfNeeded() {
        var it = cache.entrySet().iterator();
        while (usedBytes > maxBytes && it.hasNext()) {
            Map.Entry<CacheKey, Entry> entry = it.next();
            it.remove();
            usedBytes -= entry.getValue().bytes;
            Set<Query> queries = bySegment.get(entry.getKey().segmentKey());
            if (queries != null) {
                queries.remove(entry.getKey().query());
            }
            evictionCount.increment();
        }
    }

    public void onSegmentClosed(Object segmentKey) {
        synchronized (lock) {
            Set<Query> queries = bySegment.remove(segmentKey);
            if (queries == null) {
                return;
            }
            for (Query query : queries) {
                Entry removed = cache.remove(new CacheKey(segmentKey, query));
                if (removed != null) {
                    usedBytes -= removed.bytes;
                }
            }
        }
    }

    public Stats stats() {
        synchronized (lock) {
            return new Stats(hitCount.sum(), missCount.sum(), cache.size(), cacheCount, evictionCount.sum(), usedBytes);
        }
    }

    public int size() {
        synchronized (lock) {
            return cache.size();
        }
    }

    public void clear() {
        synchronized (lock) {
            cache.clear();
            bySegment.clear();
            useCounts.clear();
            usedBytes = 0;
        }
    }
}
