package com.naqqa.chatbot.ai.llm;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

public class LlmGate {

    public static final int DEFAULT_BREAKER_FAILURES = 2;
    public static final long DEFAULT_BREAKER_OPEN_MS = 300_000L;

    private final Semaphore semaphore;
    private final long waitMs;
    private final int breakerFailures;
    private final long breakerOpenMs;
    private final LongSupplier clock;
    private final AtomicInteger consecutiveTimeouts = new AtomicInteger();
    private volatile long openUntil;

    public LlmGate(int maxConcurrent, long waitMs) {
        this(maxConcurrent, waitMs, DEFAULT_BREAKER_FAILURES, DEFAULT_BREAKER_OPEN_MS, System::currentTimeMillis);
    }

    public LlmGate(int maxConcurrent, long waitMs, int breakerFailures, long breakerOpenMs, LongSupplier clock) {
        this.semaphore = new Semaphore(Math.max(1, maxConcurrent), true);
        this.waitMs = Math.max(0, waitMs);
        this.breakerFailures = Math.max(1, breakerFailures);
        this.breakerOpenMs = Math.max(0, breakerOpenMs);
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    public boolean tryAcquire() {
        if (isOpen()) {
            return false;
        }
        if (waitMs == 0) {
            return semaphore.tryAcquire();
        }
        try {
            return semaphore.tryAcquire(waitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void release() {
        semaphore.release();
    }

    public int available() {
        return semaphore.availablePermits();
    }

    public long waitMs() {
        return waitMs;
    }

    public boolean isOpen() {
        return clock.getAsLong() < openUntil;
    }

    public void recordTimeout() {
        if (consecutiveTimeouts.incrementAndGet() >= breakerFailures) {
            openUntil = clock.getAsLong() + breakerOpenMs;
            consecutiveTimeouts.set(0);
        }
    }

    public void recordSuccess() {
        consecutiveTimeouts.set(0);
    }

    public int consecutiveTimeouts() {
        return consecutiveTimeouts.get();
    }
}
