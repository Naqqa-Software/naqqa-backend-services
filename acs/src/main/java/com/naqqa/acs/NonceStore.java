package com.naqqa.acs;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

public final class NonceStore {
    private final long ttlMs;
    private final int max;
    private final Map<String, Long> seen = new HashMap<>();
    private final ArrayDeque<Object[]> queue = new ArrayDeque<>();

    public NonceStore(long ttlMs, int maxEntries) {
        if (ttlMs <= 0 || maxEntries <= 0) throw new IllegalArgumentException("invalid nonce store limits");
        this.ttlMs = ttlMs;
        this.max = maxEntries;
    }

    public synchronized boolean checkAndStore(String key, long nowMs) {
        while (!queue.isEmpty() && (Long) queue.peekFirst()[1] <= nowMs) {
            Object[] head = queue.pollFirst();
            if (head[1].equals(seen.get((String) head[0]))) seen.remove((String) head[0]);
        }
        Long exp = seen.get(key);
        if (exp != null && exp > nowMs) return false;
        if (seen.size() >= max) return false;
        long e = nowMs + ttlMs;
        seen.put(key, e);
        queue.addLast(new Object[] {key, e});
        return true;
    }

    public synchronized int size() {
        return seen.size();
    }
}
