package com.naqqa.analytics.collect;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class MemoryKeyValueStore implements KeyValueStore {

    private static final int MAX_SET = 2_000_000;

    private final Clock clock;
    private final Map<String, Entry> values = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> sets = new ConcurrentHashMap<>();
    private final Map<String, Long> setExpiry = new ConcurrentHashMap<>();
    private final Map<String, Deque<String>> lists = new ConcurrentHashMap<>();
    private volatile long lastSweep;

    public MemoryKeyValueStore() {
        this(Clock.systemUTC());
    }

    public MemoryKeyValueStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public long increment(String key, long delta, Duration ttl) {
        sweep();
        long now = clock.millis();
        Entry e = values.compute(key, (k, old) -> {
            if (old == null || old.expiresAt <= now) {
                return new Entry(String.valueOf(delta), now + ttl.toMillis());
            }
            return new Entry(String.valueOf(Long.parseLong(old.value) + delta), old.expiresAt);
        });
        return Long.parseLong(e.value);
    }

    @Override
    public String getOrSet(String key, String value, Duration ttl) {
        sweep();
        long now = clock.millis();
        Entry e = values.compute(key, (k, old) -> old == null || old.expiresAt <= now ? new Entry(value, now + ttl.toMillis()) : old);
        return e.value;
    }

    @Override
    public void hllAdd(String key, Duration ttl, String... members) {
        sweep();
        Set<String> set = sets.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet());
        setExpiry.putIfAbsent(key, clock.millis() + ttl.toMillis());
        for (String m : members) {
            if (m != null && set.size() < MAX_SET) {
                set.add(m);
            }
        }
    }

    @Override
    public long hllCount(String key) {
        Set<String> set = sets.get(key);
        return set == null ? 0 : set.size();
    }

    @Override
    public long push(String key, List<String> items, long maxLength) {
        Deque<String> list = lists.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (list) {
            for (String v : items) {
                if (list.size() >= maxLength) {
                    break;
                }
                list.addLast(v);
            }
            return list.size();
        }
    }

    @Override
    public List<String> pop(String key, int count) {
        Deque<String> list = lists.get(key);
        List<String> out = new ArrayList<>();
        if (list == null) {
            return out;
        }
        synchronized (list) {
            while (out.size() < count && !list.isEmpty()) {
                out.add(list.pollFirst());
            }
        }
        return out;
    }

    @Override
    public long length(String key) {
        Deque<String> list = lists.get(key);
        if (list == null) {
            return 0;
        }
        synchronized (list) {
            return list.size();
        }
    }

    @Override
    public boolean distributed() {
        return false;
    }

    private void sweep() {
        long now = clock.millis();
        if (now - lastSweep < 60_000L) {
            return;
        }
        lastSweep = now;
        values.entrySet().removeIf(e -> e.getValue().expiresAt <= now);
        Iterator<Map.Entry<String, Long>> it = setExpiry.entrySet().iterator();
        Set<String> expired = new HashSet<>();
        while (it.hasNext()) {
            Map.Entry<String, Long> e = it.next();
            if (e.getValue() <= now) {
                expired.add(e.getKey());
                it.remove();
            }
        }
        expired.forEach(sets::remove);
    }

    private record Entry(String value, long expiresAt) {
    }
}
