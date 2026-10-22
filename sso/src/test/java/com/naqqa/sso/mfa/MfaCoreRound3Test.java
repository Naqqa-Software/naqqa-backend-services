package com.naqqa.sso.mfa;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.p2support.MemSealer;
import com.naqqa.sso.p2support.TestClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MfaCoreRound3Test {
    private final TestClock clock = TestClock.epoch(1_700_000_000L);

    @Test
    @DisplayName("SSO-T-mfa-072 F-CORE-20 one recovery code presented to two replicas sharing a store at the same instant is accepted once")
    void recoveryCodeSingleUseAcrossReplicas() throws Exception {
        InMemoryRecoveryStore inner = new InMemoryRecoveryStore();
        AtomicReference<CyclicBarrier> gate = new AtomicReference<>();
        RecoveryStore shared = new RecoveryStore() {
            @Override
            public Optional<RecoverySet> find(String userId) {
                Optional<RecoverySet> r = inner.find(userId);
                CyclicBarrier b = gate.get();
                if (b != null) {
                    try {
                        b.await(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }
                return r;
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
            public void recordFailure(String userId, int failedAttempts, Instant at) {
                inner.recordFailure(userId, failedAttempts, at);
            }
        };
        byte[] pepper = new byte[32];
        RecoveryCodes a = new RecoveryCodes(clock, shared, pepper, 1000, 5, Duration.ofMinutes(15));
        RecoveryCodes b = new RecoveryCodes(clock, shared, pepper, 1000, 5, Duration.ofMinutes(15));
        String code = a.generate("u").get(3);
        gate.set(new CyclicBarrier(2));
        CompletableFuture<RecoveryCodes.Outcome> first = CompletableFuture.supplyAsync(() -> a.redeem("u", code));
        CompletableFuture<RecoveryCodes.Outcome> second = CompletableFuture.supplyAsync(() -> b.redeem("u", code));
        List<RecoveryCodes.Outcome> outcomes = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        gate.set(null);
        assertEquals(1, outcomes.stream().filter(o -> o == RecoveryCodes.Outcome.OK).count(), outcomes.toString());
        assertEquals(9, a.remaining("u"));
    }

    @Test
    @DisplayName("SSO-T-mfa-073 F-CORE-20 a failed redeem racing a successful one never resurrects the used code")
    void failureDoesNotResurrect() throws Exception {
        InMemoryRecoveryStore inner = new InMemoryRecoveryStore();
        AtomicReference<CyclicBarrier> gate = new AtomicReference<>();
        RecoveryStore shared = new RecoveryStore() {
            @Override
            public Optional<RecoverySet> find(String userId) {
                Optional<RecoverySet> r = inner.find(userId);
                CyclicBarrier b = gate.get();
                if (b != null) {
                    try {
                        b.await(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }
                return r;
            }

            @Override
            public void save(RecoverySet set) {
                if (gate.get() != null && set.failedAttempts() > 0) {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
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
            public void recordFailure(String userId, int failedAttempts, Instant at) {
                if (gate.get() != null) {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                inner.recordFailure(userId, failedAttempts, at);
            }
        };
        byte[] pepper = new byte[32];
        RecoveryCodes a = new RecoveryCodes(clock, shared, pepper, 1000, 5, Duration.ofMinutes(15));
        RecoveryCodes b = new RecoveryCodes(clock, shared, pepper, 1000, 5, Duration.ofMinutes(15));
        String code = a.generate("u").get(1);
        gate.set(new CyclicBarrier(2));
        CompletableFuture<RecoveryCodes.Outcome> good = CompletableFuture.supplyAsync(() -> a.redeem("u", code));
        CompletableFuture<RecoveryCodes.Outcome> bad = CompletableFuture.supplyAsync(() -> b.redeem("u", "AAAAA-AAAAA"));
        assertEquals(RecoveryCodes.Outcome.OK, good.get(10, TimeUnit.SECONDS));
        assertEquals(RecoveryCodes.Outcome.INVALID, bad.get(10, TimeUnit.SECONDS));
        gate.set(null);
        assertEquals(RecoveryCodes.Outcome.INVALID, a.redeem("u", code));
        assertEquals(9, a.remaining("u"));
    }

    @Test
    @DisplayName("SSO-T-mfa-074 F-CORE-21 an enrollment started on one replica while another confirms never replaces the confirmed TOTP credential")
    void beginNeverReplacesConfirmed() {
        InMemoryTotpCredentialStore inner = new InMemoryTotpCredentialStore();
        InMemoryTotpStepStore steps = new InMemoryTotpStepStore();
        MemSealer sealer = new MemSealer();
        AtomicReference<Runnable> onFind = new AtomicReference<>();
        TotpCredentialStore shared = new TotpCredentialStore() {
            @Override
            public Optional<TotpCredential> find(String userId) {
                Optional<TotpCredential> r = inner.find(userId);
                Runnable hook = onFind.getAndSet(null);
                if (hook != null) {
                    hook.run();
                }
                return r;
            }

            @Override
            public void save(TotpCredential credential) {
                inner.save(credential);
            }

            @Override
            public boolean confirm(String userId, byte[] expectedSealedSecret, Instant confirmedAt) {
                return inner.confirm(userId, expectedSealedSecret, confirmedAt);
            }

            @Override
            public void delete(String userId) {
                inner.delete(userId);
            }
        };
        TotpEnrollment a = new TotpEnrollment(clock, sealer, shared, steps);
        TotpEnrollment b = new TotpEnrollment(clock, sealer, shared, steps);
        String secret = a.begin("u", "naqqa", "u@example.com").secretBase32();
        String code = Totp.generate(Base32.decode(secret), clock.instant().getEpochSecond(), TotpConfig.defaults());
        onFind.set(() -> assertTrue(b.confirm("u", code)));
        assertThrows(SsoException.class, () -> a.begin("u", "naqqa", "u@example.com"));
        assertTrue(inner.find("u").orElseThrow().confirmed());
    }

    @Test
    @DisplayName("SSO-T-mfa-075 F-CORE-27 a TOTP confirmation racing a factor reset or a new enrollment on another replica never brings back or confirms the replaced pending secret")
    void confirmNeverRevivesReplacedEnrollment() {
        InMemoryTotpCredentialStore inner = new InMemoryTotpCredentialStore();
        InMemoryTotpStepStore steps = new InMemoryTotpStepStore();
        MemSealer sealer = new MemSealer();
        AtomicReference<Runnable> onAdvance = new AtomicReference<>();
        TotpStepStore hookedSteps = new TotpStepStore() {
            @Override
            public long lastStep(String userId) {
                return steps.lastStep(userId);
            }

            @Override
            public boolean advance(String userId, long step) {
                boolean r = steps.advance(userId, step);
                Runnable hook = onAdvance.getAndSet(null);
                if (hook != null) {
                    hook.run();
                }
                return r;
            }

            @Override
            public void clear(String userId) {
                steps.clear(userId);
            }
        };
        TotpEnrollment a = new TotpEnrollment(clock, sealer, inner, hookedSteps);
        TotpEnrollment b = new TotpEnrollment(clock, sealer, inner, steps);
        String first = a.begin("u", "naqqa", "u@example.com").secretBase32();
        onAdvance.set(() -> inner.delete("u"));
        a.confirm("u", Totp.generate(Base32.decode(first), clock.instant().getEpochSecond(), TotpConfig.defaults()));
        assertTrue(inner.find("u").isEmpty());

        clock.advance(Duration.ofMinutes(2));
        String second = a.begin("v", "naqqa", "v@example.com").secretBase32();
        onAdvance.set(() -> b.begin("v", "naqqa", "v@example.com"));
        a.confirm("v", Totp.generate(Base32.decode(second), clock.instant().getEpochSecond(), TotpConfig.defaults()));
        TotpCredential now = inner.find("v").orElseThrow();
        assertTrue(!now.confirmed());
    }
}
