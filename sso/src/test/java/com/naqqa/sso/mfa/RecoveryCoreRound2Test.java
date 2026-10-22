package com.naqqa.sso.mfa;

import com.naqqa.sso.p2support.TestClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryCoreRound2Test {
    private final TestClock clock = TestClock.epoch(1_700_000_000L);

    @Test
    @DisplayName("SSO-T-mfa-070 recovery redeem of one account never waits for another account's PBKDF2 or store call")
    void perAccountLock() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        InMemoryRecoveryStore inner = new InMemoryRecoveryStore();
        RecoveryStore store = new RecoveryStore() {
            @Override
            public Optional<RecoverySet> find(String userId) {
                if (userId.equals("slow")) {
                    entered.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return inner.find(userId);
            }

            @Override
            public void save(RecoverySet set) {
                inner.save(set);
            }

            @Override
            public void delete(String userId) {
                inner.delete(userId);
            }

            @Override
            public boolean markUsed(String userId, byte[] hash) {
                return inner.markUsed(userId, hash);
            }

            @Override
            public void recordFailure(String userId, int failedAttempts, java.time.Instant at) {
                inner.recordFailure(userId, failedAttempts, at);
            }
        };
        RecoveryCodes codes = new RecoveryCodes(clock, store, new byte[32], 1000, 5, Duration.ofMinutes(15));
        codes.generate("slow");
        List<String> fast = codes.generate("fast");
        CompletableFuture<RecoveryCodes.Outcome> blocked = CompletableFuture.supplyAsync(() -> codes.redeem("slow", "AAAAA-AAAAA"));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        try {
            RecoveryCodes.Outcome other = CompletableFuture.supplyAsync(() -> codes.redeem("fast", fast.get(0))).get(3, TimeUnit.SECONDS);
            assertEquals(RecoveryCodes.Outcome.OK, other);
            assertEquals(9, CompletableFuture.supplyAsync(() -> codes.remaining("fast")).get(3, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
        assertEquals(RecoveryCodes.Outcome.INVALID, blocked.get(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("SSO-T-mfa-071 concurrent redeem of the same code on one account succeeds exactly once")
    void sameAccountSerialized() throws Exception {
        InMemoryRecoveryStore store = new InMemoryRecoveryStore();
        RecoveryCodes codes = new RecoveryCodes(clock, store, new byte[32], 1000, 5, Duration.ofMinutes(15));
        String code = codes.generate("u").get(3);
        List<CompletableFuture<RecoveryCodes.Outcome>> all = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            all.add(CompletableFuture.supplyAsync(() -> codes.redeem("u", code)));
        }
        long ok = 0;
        for (CompletableFuture<RecoveryCodes.Outcome> f : all) {
            if (f.get(10, TimeUnit.SECONDS) == RecoveryCodes.Outcome.OK) {
                ok++;
            }
        }
        assertEquals(1, ok);
        assertEquals(9, codes.remaining("u"));
    }
}
