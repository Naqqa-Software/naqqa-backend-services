package com.naqqa.sso.ratelimit;

import com.naqqa.sso.SsoException;

import java.time.Clock;

public final class TokenBucket {
    private final RateLimitStore store;
    private final Clock clock;
    private final double capacity;
    private final double refillPerSecond;

    public TokenBucket(RateLimitStore store, Clock clock, double capacity, double refillPerSecond) {
        if (capacity <= 0 || refillPerSecond <= 0) {
            throw new SsoException("ratelimit_config_invalid");
        }
        this.store = store;
        this.clock = clock;
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
    }

    public Limit tryAcquire(String key, double cost) {
        return store.atomically(() -> {
            String k = "tb:" + key;
            long now = clock.millis();
            BucketState st = store.bucket(k);
            double tokens = capacity;
            if (st != null) {
                double elapsed = Math.max(0, now - st.updatedMillis()) / 1000.0;
                tokens = Math.min(capacity, st.tokens() + elapsed * refillPerSecond);
            }
            if (tokens >= cost) {
                tokens -= cost;
                store.putBucket(k, new BucketState(tokens, now));
                return new Limit(true, (long) Math.floor(tokens), 0);
            }
            store.putBucket(k, new BucketState(tokens, now));
            long wait = (long) Math.ceil((cost - tokens) / refillPerSecond * 1000.0);
            return new Limit(false, (long) Math.floor(tokens), wait);
        });
    }

    public Limit tryAcquire(String key) {
        return tryAcquire(key, 1);
    }
}
