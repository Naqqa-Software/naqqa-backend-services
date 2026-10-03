package com.naqqa.analytics.collect;

import java.time.Clock;
import java.time.Duration;

public class RateLimiter {

    private final KeyValueStore store;
    private final String prefix;
    private final int requestsPerMinute;
    private final int eventsPerMinute;
    private final Clock clock;

    public RateLimiter(KeyValueStore store, String prefix, int requestsPerMinute, int eventsPerMinute, Clock clock) {
        this.store = store;
        this.prefix = prefix;
        this.requestsPerMinute = requestsPerMinute;
        this.eventsPerMinute = eventsPerMinute;
        this.clock = clock;
    }

    public Decision check(String clientKey, int events) {
        if (clientKey == null || clientKey.isBlank()) {
            return Decision.ok();
        }
        long minute = clock.millis() / 60_000L;
        String hash = CookielessHasher.sha256(clientKey).substring(0, 16);
        long requests = store.increment(prefix + "rl:r:" + minute + ":" + hash, 1, Duration.ofSeconds(90));
        long retry = 60 - (clock.millis() / 1000L) % 60;
        if (requestsPerMinute > 0 && requests > requestsPerMinute) {
            return new Decision(false, retry);
        }
        if (eventsPerMinute > 0 && events > 0) {
            long total = store.increment(prefix + "rl:e:" + minute + ":" + hash, events, Duration.ofSeconds(90));
            if (total > eventsPerMinute) {
                return new Decision(false, retry);
            }
        }
        return Decision.ok();
    }

    public record Decision(boolean allowed, long retryAfterSeconds) {

        static Decision ok() {
            return new Decision(true, 0);
        }
    }
}
