package com.naqqa.analytics.collect;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class QualityCounters {

    public static final String RATE_LIMITED = "rate_limited";
    public static final String DROPPED = "dropped";

    private final Map<String, AtomicLong> pending = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> totals = new ConcurrentHashMap<>();
    private final long[] delays = new long[2048];
    private final AtomicLong delayIndex = new AtomicLong();
    private volatile Long lastFlushAt;
    private volatile Long lastRollupAt;

    public void reject(String reason, long count) {
        if (count <= 0 || reason == null) {
            return;
        }
        pending.computeIfAbsent(reason, k -> new AtomicLong()).addAndGet(count);
        totals.computeIfAbsent(reason, k -> new AtomicLong()).addAndGet(count);
    }

    public void delay(long ms) {
        if (ms < 0) {
            return;
        }
        long i = delayIndex.getAndIncrement();
        delays[(int) (i % delays.length)] = ms;
    }

    public Map<String, Long> drain() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map.Entry<String, AtomicLong> e : pending.entrySet()) {
            long v = e.getValue().getAndSet(0);
            if (v > 0) {
                out.put(e.getKey(), v);
            }
        }
        return out;
    }

    public void restore(Map<String, Long> deltas) {
        if (deltas == null) {
            return;
        }
        deltas.forEach((k, v) -> pending.computeIfAbsent(k, x -> new AtomicLong()).addAndGet(v));
    }

    public Map<String, Long> totals() {
        Map<String, Long> out = new LinkedHashMap<>();
        totals.forEach((k, v) -> out.put(k, v.get()));
        return out;
    }

    public long avgDelayMs() {
        long[] s = samples();
        if (s.length == 0) {
            return 0;
        }
        long sum = 0;
        for (long v : s) {
            sum += v;
        }
        return sum / s.length;
    }

    public long p95DelayMs() {
        long[] s = samples();
        if (s.length == 0) {
            return 0;
        }
        Arrays.sort(s);
        return s[(int) Math.min(s.length - 1, Math.ceil(s.length * 0.95) - 1)];
    }

    private long[] samples() {
        long n = Math.min(delayIndex.get(), delays.length);
        List<Long> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(delays[i]);
        }
        return out.stream().mapToLong(Long::longValue).toArray();
    }

    public Long lastFlushAt() {
        return lastFlushAt;
    }

    public void flushed(long at) {
        lastFlushAt = at;
    }

    public Long lastRollupAt() {
        return lastRollupAt;
    }

    public void rolledUp(long at) {
        lastRollupAt = at;
    }
}
