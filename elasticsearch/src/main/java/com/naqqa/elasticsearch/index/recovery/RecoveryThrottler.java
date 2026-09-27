package com.naqqa.elasticsearch.index.recovery;

import com.naqqa.elasticsearch.common.unit.ByteSizeValue;
import com.naqqa.elasticsearch.store.SimpleRateLimiter;

public final class RecoveryThrottler {

    private volatile SimpleRateLimiter limiter;
    private volatile boolean unlimited;

    public RecoveryThrottler(ByteSizeValue maxBytesPerSec) {
        setMaxBytesPerSec(maxBytesPerSec);
    }

    public static RecoveryThrottler unthrottled() {
        return new RecoveryThrottler(ByteSizeValue.ofBytes(-1));
    }

    public synchronized void setMaxBytesPerSec(ByteSizeValue maxBytesPerSec) {
        if (maxBytesPerSec == null || maxBytesPerSec.getBytes() <= 0) {
            this.unlimited = true;
            this.limiter = null;
            return;
        }
        double mbPerSec = maxBytesPerSec.getBytes() / (1024.0 * 1024.0);
        if (limiter == null) {
            this.limiter = new SimpleRateLimiter(mbPerSec);
        } else {
            this.limiter.setMBPerSec(mbPerSec);
        }
        this.unlimited = false;
    }

    public ByteSizeValue maxBytesPerSec() {
        if (unlimited) {
            return ByteSizeValue.ofBytes(-1);
        }
        return ByteSizeValue.ofBytes((long) (limiter.getMBPerSec() * 1024.0 * 1024.0));
    }

    public long throttle(long bytes) {
        if (unlimited || bytes <= 0) {
            return 0L;
        }
        return limiter.pause(bytes);
    }
}
