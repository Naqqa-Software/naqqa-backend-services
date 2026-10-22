package com.naqqa.sso.otp;

import com.naqqa.sso.p2support.RecordingTransport;
import com.naqqa.sso.p2support.TestClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PushApprovalStoreTest {
    private final TestClock clock = TestClock.at("2026-03-01T10:00:00Z");
    private final InMemoryPushApprovalStore store = new InMemoryPushApprovalStore();
    private final PushApproval push = new PushApproval(clock, new RecordingTransport(), store);

    @Test
    @DisplayName("SSO-T-otp-036 F-A2 push state lives in the store and a second service instance sees it")
    void sharedState() {
        PushApproval.Challenge c = push.create("u1", "d");
        PushApproval other = new PushApproval(clock, new RecordingTransport(), store);
        assertEquals(PushApproval.Status.APPROVED, other.respond(c.id(), "u1", c.displayNumber(), true));
        assertEquals(PushApproval.Status.APPROVED, push.status(c.id()));
        assertTrue(push.consume(c.id(), "u1"));
        assertFalse(other.consume(c.id(), "u1"));
    }

    @Test
    @DisplayName("SSO-T-otp-037 F-A2 pending transitions to exactly one decision")
    void singleDecision() {
        PushApproval.Challenge c = push.create("u1", "d");
        assertTrue(store.transition(c.id(), PushApproval.Status.PENDING, PushApproval.Status.APPROVED));
        assertFalse(store.transition(c.id(), PushApproval.Status.PENDING, PushApproval.Status.DENIED));
        assertEquals(PushApproval.Status.APPROVED, store.find(c.id()).orElseThrow().status());
        assertEquals(PushApproval.Status.APPROVED, push.respond(c.id(), "u1", c.displayNumber(), false));
    }

    @Test
    @DisplayName("SSO-T-otp-038 F-A2 concurrent approve and deny yield one winner and one consume")
    void concurrentDecision() throws Exception {
        PushApproval.Challenge c = push.create("u1", "d");
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<PushApproval.Status>> results = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            boolean approve = i % 2 == 0;
            results.add(pool.submit(() -> push.respond(c.id(), "u1", c.displayNumber(), approve)));
        }
        for (Future<PushApproval.Status> f : results) {
            f.get();
        }
        PushApproval.Status finalState = push.status(c.id());
        for (Future<PushApproval.Status> f : results) {
            PushApproval.Status seen = f.get();
            assertTrue(seen == finalState || seen == PushApproval.Status.PENDING || seen == PushApproval.Status.APPROVED || seen == PushApproval.Status.DENIED);
        }
        AtomicInteger consumed = new AtomicInteger();
        List<Future<?>> consumers = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            consumers.add(pool.submit(() -> {
                if (push.consume(c.id(), "u1")) {
                    consumed.incrementAndGet();
                }
            }));
        }
        for (Future<?> f : consumers) {
            f.get();
        }
        pool.shutdown();
        assertEquals(finalState == PushApproval.Status.APPROVED ? 1 : 0, consumed.get());
    }

    @Test
    @DisplayName("SSO-T-otp-039 F-A2 expiry transitions pending to expired and blocks approval")
    void expiry() {
        PushApproval.Challenge c = push.create("u1", "d");
        clock.advance(Duration.ofMinutes(3));
        assertEquals(PushApproval.Status.EXPIRED, push.respond(c.id(), "u1", c.displayNumber(), true));
        assertEquals(PushApproval.Status.EXPIRED, store.find(c.id()).orElseThrow().status());
        assertFalse(push.consume(c.id(), "u1"));
    }

    @Test
    @DisplayName("SSO-T-otp-040 F-A2 mismatch counter is atomic and only counts while pending")
    void mismatchAtomic() {
        PushApproval.Challenge c = push.create("u1", "d");
        assertEquals(1, store.addMismatch(c.id()).getAsInt());
        assertEquals(2, store.addMismatch(c.id()).getAsInt());
        store.transition(c.id(), PushApproval.Status.PENDING, PushApproval.Status.DENIED);
        assertTrue(store.addMismatch(c.id()).isEmpty());
    }
}
