package com.naqqa.sso.ratelimit;

import com.naqqa.sso.SsoException;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class SlidingWindowLimiter {
    private final RateLimitStore store;
    private final Clock clock;
    private final int limit;
    private final long windowMillis;

    public SlidingWindowLimiter(RateLimitStore store, Clock clock, int limit, Duration window) {
        if (limit < 1 || window.isZero() || window.isNegative()) {
            throw new SsoException("ratelimit_config_invalid");
        }
        this.store = store;
        this.clock = clock;
        this.limit = limit;
        this.windowMillis = window.toMillis();
    }

    public Limit tryAcquire(String key) {
        return store.atomically(() -> {
            String k = "sw:" + key;
            long now = clock.millis();
            List<Long> kept = new ArrayList<>();
            for (long t : store.window(k)) {
                if (t > now - windowMillis) {
                    kept.add(t);
                }
            }
            if (kept.size() >= limit) {
                store.putWindow(k, kept);
                long oldest = kept.get(0);
                return new Limit(false, 0, oldest + windowMillis - now);
            }
            kept.add(now);
            store.putWindow(k, kept);
            return new Limit(true, limit - kept.size(), 0);
        });
    }
}
