package com.naqqa.elasticsearch.store;

import java.util.concurrent.atomic.AtomicLong;

public final class SimpleRateLimiter extends RateLimiter {

    public static final long MIN_PAUSE_CHECK_BYTES = 1024;

    private volatile double mbPerSec;
    private volatile long minPauseCheckBytes;
    private final AtomicLong lastNS = new AtomicLong();
    private final AtomicLong bytesSinceLastPause = new AtomicLong();

    public SimpleRateLimiter(double mbPerSec) {
        setMBPerSec(mbPerSec);
        lastNS.set(System.nanoTime());
    }

    @Override
    public void setMBPerSec(double mbPerSec) {
        this.mbPerSec = mbPerSec;
        this.minPauseCheckBytes = (long) ((MIN_PAUSE_CHECK_BYTES / 1024.0 / 1024.0) * mbPerSec) + 1;
    }

    @Override
    public double getMBPerSec() {
        return mbPerSec;
    }

    @Override
    public long getMinPauseCheckBytes() {
        return minPauseCheckBytes;
    }

    @Override
    public long pause(long bytes) {
        long total = bytesSinceLastPause.addAndGet(bytes);
        if (total < minPauseCheckBytes) {
            return 0;
        }
        bytesSinceLastPause.addAndGet(-total);
        double secondsToPause = (total / 1024.0 / 1024.0) / mbPerSec;
        long targetNS = lastNS.get() + (long) (secondsToPause * 1_000_000_000.0);
        long curNS = System.nanoTime();
        if (lastNS.get() < curNS) {
            targetNS = curNS + (long) (secondsToPause * 1_000_000_000.0);
        }
        lastNS.set(targetNS);
        long pauseNS = targetNS - curNS;
        if (pauseNS <= 0) {
            return 0;
        }
        long pauseMS = pauseNS / 1_000_000;
        int pauseNanos = (int) (pauseNS % 1_000_000);
        try {
            Thread.sleep(pauseMS, pauseNanos);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return pauseNS;
    }
}
