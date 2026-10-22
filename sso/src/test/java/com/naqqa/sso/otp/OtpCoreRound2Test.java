package com.naqqa.sso.otp;

import com.naqqa.sso.p2support.RecordingTransport;
import com.naqqa.sso.p2support.TestClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OtpCoreRound2Test {
    private static final int REPLICAS = 10;
    private final TestClock clock = TestClock.at("2026-03-01T10:00:00Z");
    private final byte[] pepper = "0123456789abcdef0123456789abcdef".getBytes();

    static final class SharedStore implements OtpStore {
        final Map<String, OtpRecord> records = new ConcurrentHashMap<>();
        final InMemoryOtpStore sends = new InMemoryOtpStore();
        volatile CountDownLatch gate = new CountDownLatch(0);

        @Override
        public void save(OtpRecord r) {
            records.merge(r.id(), r, (old, n) -> new OtpRecord(old.id(), old.destinationKey(), old.purpose(), old.codeMac(),
                    old.createdAt(), old.expiresAt(), Math.max(old.attempts(), n.attempts()), old.consumed() || n.consumed()));
        }

        @Override
        public Optional<OtpRecord> find(String id) {
            CountDownLatch g = gate;
            g.countDown();
            try {
                g.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Optional.ofNullable(records.get(id));
        }

        @Override
        public Optional<OtpRecord> recordAttempt(String id, int maxAttempts) {
            OtpRecord[] out = new OtpRecord[1];
            records.computeIfPresent(id, (k, v) -> {
                if (v.consumed() || v.attempts() >= maxAttempts) {
                    return v;
                }
                out[0] = v.withAttempts(v.attempts() + 1);
                return out[0];
            });
            return Optional.ofNullable(out[0]);
        }

        @Override
        public boolean consume(String id) {
            boolean[] flipped = new boolean[1];
            records.computeIfPresent(id, (k, v) -> {
                if (v.consumed()) {
                    return v;
                }
                flipped[0] = true;
                return v.withConsumed();
            });
            return flipped[0];
        }

        @Override
        public void recordSend(String destinationKey, Instant at) {
            sends.recordSend(destinationKey, at);
        }

        @Override
        public Optional<Instant> lastSend(String destinationKey) {
            return sends.lastSend(destinationKey);
        }

        @Override
        public int sendsSince(String destinationKey, Instant since) {
            return sends.sendsSince(destinationKey, since);
        }

        @Override
        public void invalidateDestination(String destinationKey) {
            records.replaceAll((k, v) -> v.destinationKey().equals(destinationKey) ? v.withConsumed() : v);
        }
    }

    @Test
    @DisplayName("SSO-T-otp-042 parallel guesses on separate replicas sharing one store are capped by maxAttempts (atomic increment-and-check)")
    void parallelGuessesCapped() throws Exception {
        SharedStore store = new SharedStore();
        RecordingTransport transport = new RecordingTransport();
        List<OtpService> replicas = new ArrayList<>();
        for (int i = 0; i < REPLICAS; i++) {
            replicas.add(new OtpService(clock, pepper, store, transport, OtpConfig.defaults()));
        }
        OtpService.Requested r = replicas.get(0).request(Channel.EMAIL, "a@x.io", "login", true);
        String code = transport.last().vars().get("code");
        String wrong = code.equals("000000") ? "111111" : "000000";
        store.gate = new CountDownLatch(REPLICAS);
        List<CompletableFuture<OtpService.Outcome>> all = new ArrayList<>();
        for (OtpService s : replicas) {
            all.add(CompletableFuture.supplyAsync(() -> s.verify(r.challengeId(), "login", wrong)));
        }
        int evaluated = 0;
        for (CompletableFuture<OtpService.Outcome> f : all) {
            OtpService.Outcome o = f.get(10, TimeUnit.SECONDS);
            if (o == OtpService.Outcome.INVALID) {
                evaluated++;
            }
        }
        int max = OtpConfig.defaults().maxAttempts();
        assertTrue(evaluated <= max - 1, "guesses evaluated: " + evaluated);
        assertEquals(max, store.records.get(r.challengeId()).attempts());
        assertTrue(store.records.get(r.challengeId()).consumed());
        assertEquals(OtpService.Outcome.EXHAUSTED, replicas.get(1).verify(r.challengeId(), "login", code));
    }

    @Test
    @DisplayName("SSO-T-otp-043 the right code on two replicas at once is accepted exactly once")
    void parallelSuccessOnce() throws Exception {
        SharedStore store = new SharedStore();
        RecordingTransport transport = new RecordingTransport();
        OtpService a = new OtpService(clock, pepper, store, transport, OtpConfig.defaults());
        OtpService b = new OtpService(clock, pepper, store, transport, OtpConfig.defaults());
        OtpService.Requested r = a.request(Channel.EMAIL, "a@x.io", "login", true);
        String code = transport.last().vars().get("code");
        store.gate = new CountDownLatch(2);
        CompletableFuture<OtpService.Outcome> fa = CompletableFuture.supplyAsync(() -> a.verify(r.challengeId(), "login", code));
        CompletableFuture<OtpService.Outcome> fb = CompletableFuture.supplyAsync(() -> b.verify(r.challengeId(), "login", code));
        int ok = (fa.get(10, TimeUnit.SECONDS) == OtpService.Outcome.OK ? 1 : 0) + (fb.get(10, TimeUnit.SECONDS) == OtpService.Outcome.OK ? 1 : 0);
        assertEquals(1, ok);
    }
}
