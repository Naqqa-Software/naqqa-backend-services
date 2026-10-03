package com.naqqa.chatbot.ai.llm;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class LlmGate {

    private final Semaphore semaphore;
    private final long waitMs;

    public LlmGate(int maxConcurrent, long waitMs) {
        this.semaphore = new Semaphore(Math.max(1, maxConcurrent), true);
        this.waitMs = Math.max(0, waitMs);
    }

    public boolean tryAcquire() {
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
}
