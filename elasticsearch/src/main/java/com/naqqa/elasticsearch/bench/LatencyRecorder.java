package com.naqqa.elasticsearch.bench;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

public final class LatencyRecorder {

    private final CopyOnWriteArrayList<Long> samplesNanos = new CopyOnWriteArrayList<>();
    private final AtomicLong errors = new AtomicLong();

    public void record(long nanos) {
        samplesNanos.add(nanos);
    }

    public void recordError() {
        errors.incrementAndGet();
    }

    public long errorCount() {
        return errors.get();
    }

    public int count() {
        return samplesNanos.size();
    }

    public double percentileMillis(double p) {
        if (samplesNanos.isEmpty()) {
            return 0.0;
        }
        List<Long> sorted = new ArrayList<>(samplesNanos);
        Collections.sort(sorted);
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        idx = Math.max(0, Math.min(sorted.size() - 1, idx));
        return sorted.get(idx) / 1_000_000.0;
    }

    public double maxMillis() {
        if (samplesNanos.isEmpty()) {
            return 0.0;
        }
        long max = 0L;
        for (long n : samplesNanos) {
            max = Math.max(max, n);
        }
        return max / 1_000_000.0;
    }
}
