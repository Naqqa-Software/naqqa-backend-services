package com.naqqa.chatbot;

import com.naqqa.chatbot.service.ChatRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatRateLimiterTest {

    @Test
    void inMemoryLimitAndWindowReset() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        ChatRateLimiter limiter = new ChatRateLimiter(null, clock::get);
        for (int i = 0; i < 20; i++) {
            assertTrue(limiter.tryAcquire("conv:a", 20, Duration.ofMinutes(1)));
        }
        assertFalse(limiter.tryAcquire("conv:a", 20, Duration.ofMinutes(1)));
        assertTrue(limiter.tryAcquire("conv:b", 20, Duration.ofMinutes(1)));
        clock.addAndGet(60_000L);
        assertTrue(limiter.tryAcquire("conv:a", 20, Duration.ofMinutes(1)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void usesRedisCounterWhenAvailable() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment(anyString())).thenReturn(1L, 2L, 3L);
        ChatRateLimiter limiter = new ChatRateLimiter(redis, () -> 5_000L);
        assertTrue(limiter.tryAcquire("conv:x", 2, Duration.ofMinutes(1)));
        assertTrue(limiter.tryAcquire("conv:x", 2, Duration.ofMinutes(1)));
        assertFalse(limiter.tryAcquire("conv:x", 2, Duration.ofMinutes(1)));
    }

    @Test
    void fallsBackToMemoryWhenRedisFails() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenThrow(new RuntimeException("down"));
        ChatRateLimiter limiter = new ChatRateLimiter(redis, () -> 5_000L);
        assertTrue(limiter.tryAcquire("conv:y", 1, Duration.ofMinutes(1)));
        assertFalse(limiter.tryAcquire("conv:y", 1, Duration.ofMinutes(1)));
    }
}
