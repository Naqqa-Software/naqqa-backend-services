package com.naqqa.chatbot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

@Slf4j
public class ChatRateLimiter {

    private static final int MAX_KEYS = 50_000;
    private static final long REDIS_RETRY_AFTER_MILLIS = 60_000L;

    private final StringRedisTemplate redis;
    private final LongSupplier clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private volatile long redisDisabledUntil;
    private String prefix = "chat:rl:";

    private static final class Window {
        private final long start;
        private final AtomicInteger count = new AtomicInteger();

        private Window(long start) {
            this.start = start;
        }
    }

    public static ChatRateLimiter inMemory() {
        return new ChatRateLimiter(null, System::currentTimeMillis);
    }

    public ChatRateLimiter(ObjectProvider<StringRedisTemplate> redis, String prefix) {
        this(redis.getIfAvailable(), System::currentTimeMillis);
        this.prefix = prefix == null || prefix.isBlank() ? "chat:rl:" : prefix;
    }

    public ChatRateLimiter(StringRedisTemplate redis, LongSupplier clock) {
        this.redis = redis;
        this.clock = clock;
    }

    public boolean tryAcquire(String key, int limit, Duration window) {
        long now = clock.getAsLong();
        if (redis != null && now >= redisDisabledUntil) {
            try {
                String redisKey = prefix + key + ":" + (now / window.toMillis());
                Long count = redis.opsForValue().increment(redisKey);
                if (count != null && count == 1L) {
                    redis.expire(redisKey, window.plusSeconds(5));
                }
                if (count != null) {
                    return count <= limit;
                }
            } catch (Exception e) {
                redisDisabledUntil = now + REDIS_RETRY_AFTER_MILLIS;
                log.warn("Chat rate limiter falling back to memory: {}", e.getMessage());
            }
        }
        return tryAcquireLocal(key, limit, window.toMillis(), now);
    }

    private boolean tryAcquireLocal(String key, int limit, long windowMillis, long now) {
        if (windows.size() > MAX_KEYS) {
            windows.entrySet().removeIf(e -> now - e.getValue().start > windowMillis);
            if (windows.size() > MAX_KEYS) {
                windows.clear();
            }
        }
        Window w = windows.compute(key, (k, existing) ->
                existing == null || now - existing.start >= windowMillis ? new Window(now) : existing);
        return w.count.incrementAndGet() <= limit;
    }
}
