package com.naqqa.elasticsearch.action.byquery;

import java.util.concurrent.atomic.AtomicLong;

public final class ThrottleController {

    private volatile double requestsPerSecond;
    private final AtomicLong throttledMillis = new AtomicLong();
    private volatile long throttledUntilMillis;

    public ThrottleController(double requestsPerSecond) {
        this.requestsPerSecond = requestsPerSecond;
    }

    public double requestsPerSecond() {
        return requestsPerSecond;
    }

    public void rethrottle(double newRequestsPerSecond) {
        this.requestsPerSecond = newRequestsPerSecond;
    }

    public long throttledMillis() {
        return throttledMillis.get();
    }

    public long throttledUntilMillis() {
        return throttledUntilMillis;
    }

    public void throttleBeforeNextBatch(int docsInLastBatch) throws InterruptedException {
        double rate = requestsPerSecond;
        if (rate <= 0d || docsInLastBatch <= 0) {
            throttledUntilMillis = 0L;
            return;
        }
        long waitMillis = (long) Math.ceil((docsInLastBatch / rate) * 1000.0);
        if (waitMillis <= 0) {
            throttledUntilMillis = 0L;
            return;
        }
        throttledMillis.addAndGet(waitMillis);
        throttledUntilMillis = System.currentTimeMillis() + waitMillis;
        Thread.sleep(waitMillis);
        throttledUntilMillis = 0L;
    }
}
