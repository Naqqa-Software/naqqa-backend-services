package com.naqqa.elasticsearch.node.monitor;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class NodeCounters {

    public static final class Timed {
        private final LongAdder total = new LongAdder();
        private final LongAdder timeNanos = new LongAdder();
        private final LongAdder failed = new LongAdder();
        private final AtomicLong current = new AtomicLong();

        public long start() {
            current.incrementAndGet();
            return System.nanoTime();
        }

        public void end(long startNanos, boolean success) {
            current.decrementAndGet();
            total.increment();
            timeNanos.add(System.nanoTime() - startNanos);
            if (!success) {
                failed.increment();
            }
        }

        public long total() {
            return total.sum();
        }

        public long timeMillis() {
            return timeNanos.sum() / 1_000_000L;
        }

        public long current() {
            return current.get();
        }

        public long failed() {
            return failed.sum();
        }
    }

    public final Timed indexing = new Timed();
    public final Timed deletes = new Timed();
    public final Timed getExists = new Timed();
    public final Timed getMissing = new Timed();
    public final Timed query = new Timed();
    public final Timed fetch = new Timed();
    public final Timed scroll = new Timed();
    public final Timed suggest = new Timed();
    public final Timed refresh = new Timed();
    public final Timed flush = new Timed();
    public final Timed merge = new Timed();
    public final LongAdder mergedDocs = new LongAdder();
    public final LongAdder httpTotalOpened = new LongAdder();
    public final AtomicLong httpCurrentOpen = new AtomicLong();
    public final LongAdder restRequests = new LongAdder();
}
