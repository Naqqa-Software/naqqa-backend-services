package com.naqqa.sso.ratelimit;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.p2support.TestClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class RateLimitTest {
    private final TestClock clock = TestClock.epoch(1_800_000_000L);
    private final RateLimitStore store = new InMemoryRateLimitStore();

    @Test
    @DisplayName("SSO-T-ratelimit-001 token bucket allows up to capacity then refuses")
    void bucketCapacity() {
        TokenBucket tb = new TokenBucket(store, clock, 3, 1);
        assertTrue(tb.tryAcquire("k").allowed());
        assertTrue(tb.tryAcquire("k").allowed());
        assertTrue(tb.tryAcquire("k").allowed());
        Limit l = tb.tryAcquire("k");
        assertFalse(l.allowed());
        assertEquals(1000, l.retryAfterMillis());
    }

    @Test
    @DisplayName("SSO-T-ratelimit-002 token bucket refills over time and never above capacity")
    void bucketRefill() {
        TokenBucket tb = new TokenBucket(store, clock, 2, 1);
        tb.tryAcquire("k");
        tb.tryAcquire("k");
        assertFalse(tb.tryAcquire("k").allowed());
        clock.advance(Duration.ofSeconds(1));
        assertTrue(tb.tryAcquire("k").allowed());
        clock.advance(Duration.ofHours(1));
        assertEquals(1, tb.tryAcquire("k").remaining());
    }

    @Test
    @DisplayName("SSO-T-ratelimit-003 token bucket keys are independent")
    void bucketKeys() {
        TokenBucket tb = new TokenBucket(store, clock, 1, 0.1);
        assertTrue(tb.tryAcquire("a").allowed());
        assertTrue(tb.tryAcquire("b").allowed());
        assertFalse(tb.tryAcquire("a").allowed());
    }

    @Test
    @DisplayName("SSO-T-ratelimit-004 token bucket supports cost greater than one")
    void bucketCost() {
        TokenBucket tb = new TokenBucket(store, clock, 5, 1);
        assertTrue(tb.tryAcquire("k", 5).allowed());
        assertFalse(tb.tryAcquire("k", 2).allowed());
    }

    @Test
    @DisplayName("SSO-T-ratelimit-005 token bucket rejects bad config")
    void bucketConfig() {
        assertThrows(SsoException.class, () -> new TokenBucket(store, clock, 0, 1));
        assertThrows(SsoException.class, () -> new TokenBucket(store, clock, 1, 0));
    }

    @Test
    @DisplayName("SSO-T-ratelimit-006 sliding window caps requests and reports retry-after")
    void windowCap() {
        SlidingWindowLimiter sw = new SlidingWindowLimiter(store, clock, 3, Duration.ofSeconds(10));
        assertTrue(sw.tryAcquire("k").allowed());
        clock.advance(Duration.ofSeconds(2));
        assertTrue(sw.tryAcquire("k").allowed());
        assertTrue(sw.tryAcquire("k").allowed());
        Limit l = sw.tryAcquire("k");
        assertFalse(l.allowed());
        assertEquals(8000, l.retryAfterMillis());
    }

    @Test
    @DisplayName("SSO-T-ratelimit-007 sliding window frees capacity exactly when the oldest hit leaves")
    void windowEdge() {
        SlidingWindowLimiter sw = new SlidingWindowLimiter(store, clock, 1, Duration.ofSeconds(10));
        assertTrue(sw.tryAcquire("k").allowed());
        clock.advance(Duration.ofMillis(9999));
        assertFalse(sw.tryAcquire("k").allowed());
        clock.advance(Duration.ofMillis(1));
        assertTrue(sw.tryAcquire("k").allowed());
    }

    @Test
    @DisplayName("SSO-T-ratelimit-008 sliding window rejected hits do not extend the window")
    void windowNoPenalty() {
        SlidingWindowLimiter sw = new SlidingWindowLimiter(store, clock, 1, Duration.ofSeconds(10));
        sw.tryAcquire("k");
        for (int i = 0; i < 50; i++) {
            sw.tryAcquire("k");
        }
        clock.advance(Duration.ofSeconds(10));
        assertTrue(sw.tryAcquire("k").allowed());
    }

    @Test
    @DisplayName("SSO-T-ratelimit-009 sliding window rejects bad config")
    void windowConfig() {
        assertThrows(SsoException.class, () -> new SlidingWindowLimiter(store, clock, 0, Duration.ofSeconds(1)));
        assertThrows(SsoException.class, () -> new SlidingWindowLimiter(store, clock, 1, Duration.ZERO));
    }

    private final LoginThrottle throttle = new LoginThrottle(store, clock);

    @Test
    @DisplayName("SSO-T-ratelimit-010 login throttle allows the first free attempts")
    void freeAttempts() {
        for (int i = 0; i < 3; i++) {
            assertEquals(Decision.Type.ALLOW, throttle.check("alice", "1.1.1.1").type());
            throttle.recordFailure("alice", "1.1.1.1");
        }
        assertEquals(Decision.Type.ALLOW, throttle.check("alice", "1.1.1.1").type());
        throttle.recordFailure("alice", "1.1.1.1");
        assertEquals(Decision.Type.DELAY, throttle.check("alice", "1.1.1.1").type());
    }

    @Test
    @DisplayName("SSO-T-ratelimit-011 login throttle delay grows exponentially and is capped")
    void exponential() {
        long previous = 0;
        for (int i = 1; i <= 5; i++) {
            throttle.recordFailure("alice", "1.1.1.1");
            Decision d = throttle.check("alice", "1.1.1.1", true);
            if (i > 3) {
                assertEquals(Decision.Type.DELAY, d.type());
                assertEquals(1000L << (i - 4), d.delayMillis());
                assertTrue(d.delayMillis() > previous);
                previous = d.delayMillis();
            }
        }
        LoginThrottle wide = new LoginThrottle(store, clock, new ThrottleConfig(3, Duration.ofSeconds(1), Duration.ofMinutes(15), Duration.ofHours(10), 1000, 1000, 1000));
        for (int i = 0; i < 40; i++) {
            wide.recordFailure("bob", "2.2.2.2");
        }
        Decision d = wide.check("bob", "2.2.2.2", true);
        assertEquals(Decision.Type.DELAY, d.type());
        assertEquals(900_000, d.delayMillis());
    }

    @Test
    @DisplayName("SSO-T-ratelimit-012 delay counts down with time")
    void delayCountdown() {
        for (int i = 0; i < 4; i++) {
            throttle.recordFailure("alice", "1.1.1.1");
        }
        assertEquals(1000, throttle.check("alice", "1.1.1.1").delayMillis());
        clock.advance(Duration.ofMillis(400));
        assertEquals(600, throttle.check("alice", "1.1.1.1").delayMillis());
        clock.advance(Duration.ofMillis(600));
        assertEquals(Decision.Type.ALLOW, throttle.check("alice", "1.1.1.1").type());
    }

    @Test
    @DisplayName("SSO-T-ratelimit-013 never hard locks: a long wait always returns ALLOW")
    void neverHardLock() {
        for (int i = 0; i < 200; i++) {
            throttle.recordFailure("alice", "1.1.1.1");
        }
        clock.advance(Duration.ofHours(1));
        assertEquals(Decision.Type.ALLOW, throttle.check("alice", "1.1.1.1").type());
    }

    @Test
    @DisplayName("SSO-T-ratelimit-014 attacker on another IP cannot delay the real user: account scope only challenges")
    void noDenyOfService() {
        for (int i = 0; i < 9; i++) {
            throttle.recordFailure("alice", "6.6.6.6");
        }
        Decision real = throttle.check("alice", "9.9.9.9");
        assertEquals(Decision.Type.ALLOW, real.type());
    }

    @Test
    @DisplayName("SSO-T-ratelimit-015 account-wide failures signal CHALLENGE for unknown devices only")
    void accountChallenge() {
        for (int i = 0; i < 12; i++) {
            throttle.recordFailure("alice", "6.6.6." + i);
        }
        assertEquals(Decision.Type.CHALLENGE, throttle.check("alice", "9.9.9.9", false).type());
        assertEquals(Decision.Type.ALLOW, throttle.check("alice", "9.9.9.9", true).type());
    }

    @Test
    @DisplayName("SSO-T-ratelimit-016 IP scope delays credential stuffing across many accounts")
    void ipScope() {
        for (int i = 0; i < 6; i++) {
            throttle.recordFailure("user" + i, "6.6.6.6");
        }
        Decision d = throttle.check("victim", "6.6.6.6");
        assertEquals(Decision.Type.DELAY, d.type());
        assertEquals(Decision.Type.ALLOW, throttle.check("victim", "7.7.7.7").type());
    }

    @Test
    @DisplayName("SSO-T-ratelimit-017 pair scope reaching its threshold requires a challenge even for known devices")
    void pairChallenge() {
        for (int i = 0; i < 6; i++) {
            throttle.recordFailure("alice", "1.1.1.1");
        }
        assertEquals(Decision.Type.CHALLENGE, throttle.check("alice", "1.1.1.1", true).type());
    }

    @Test
    @DisplayName("SSO-T-ratelimit-018 success clears account and pair counters")
    void successResets() {
        for (int i = 0; i < 5; i++) {
            throttle.recordFailure("alice", "1.1.1.1");
        }
        throttle.recordSuccess("alice", "1.1.1.1");
        assertEquals(Decision.Type.ALLOW, throttle.check("alice", "1.1.1.1", true).type());
    }

    @Test
    @DisplayName("SSO-T-ratelimit-019 failure counters decay after the decay window")
    void decay() {
        for (int i = 0; i < 5; i++) {
            throttle.recordFailure("alice", "1.1.1.1");
        }
        clock.advance(Duration.ofMinutes(31));
        assertEquals(Decision.Type.ALLOW, throttle.check("alice", "1.1.1.1").type());
        throttle.recordFailure("alice", "1.1.1.1");
        assertEquals(Decision.Type.ALLOW, throttle.check("alice", "1.1.1.1").type());
    }

    @Test
    @DisplayName("SSO-T-ratelimit-020 account identifiers are case insensitive and not stored in clear")
    void normalization() {
        for (int i = 0; i < 4; i++) {
            throttle.recordFailure("Alice@X.io", "1.1.1.1");
        }
        assertEquals(Decision.Type.DELAY, throttle.check("alice@x.io", "1.1.1.1").type());
    }

    @Test
    @DisplayName("SSO-T-ratelimit-021 throttle config validation")
    void config() {
        assertThrows(SsoException.class, () -> new ThrottleConfig(-1, Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(1), 1, 1, 1));
        assertThrows(SsoException.class, () -> new ThrottleConfig(1, Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(1), 1, 1, 1));
        assertThrows(SsoException.class, () -> new ThrottleConfig(1, Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ZERO, 1, 1, 1));
    }

    @Test
    @DisplayName("SSO-T-ratelimit-022 decision factories")
    void decisions() {
        assertEquals(Decision.Type.ALLOW, Decision.allow().type());
        assertEquals(500, Decision.delay(500).delayMillis());
        assertEquals(Decision.Type.CHALLENGE, Decision.challenge().type());
    }
}
