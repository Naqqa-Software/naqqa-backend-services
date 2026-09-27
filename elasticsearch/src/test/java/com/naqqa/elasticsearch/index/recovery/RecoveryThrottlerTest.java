package com.naqqa.elasticsearch.index.recovery;

import com.naqqa.elasticsearch.common.unit.ByteSizeValue;
import com.naqqa.elasticsearch.test.Test;

import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class RecoveryThrottlerTest {

    @Test
    public void unthrottledDoesNotPause() {
        RecoveryThrottler throttler = RecoveryThrottler.unthrottled();
        long start = System.nanoTime();
        throttler.throttle(10_000_000);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMillis < 200, "expected no meaningful pause when unthrottled, took " + elapsedMillis + "ms");
    }

    @Test
    public void throttlingCapsObservedTransferRate() {
        RecoveryThrottler throttler = new RecoveryThrottler(ByteSizeValue.ofKb(20));
        long totalBytes = 40L * 1024L;
        long remaining = totalBytes;
        int chunk = 4 * 1024;
        long start = System.nanoTime();
        while (remaining > 0) {
            long n = Math.min(chunk, remaining);
            throttler.throttle(n);
            remaining -= n;
        }
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMillis >= 1500, "expected throttling to slow a 40KB transfer at 20KB/s to roughly 2s, took " + elapsedMillis + "ms");
    }
}
