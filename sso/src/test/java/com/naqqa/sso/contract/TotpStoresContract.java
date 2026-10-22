package com.naqqa.sso.contract;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.mfa.TotpConfig;
import com.naqqa.sso.mfa.TotpCredential;
import com.naqqa.sso.mfa.TotpCredentialStore;
import com.naqqa.sso.mfa.TotpStepStore;
import com.naqqa.sso.util.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public abstract class TotpStoresContract {
    protected TotpStepStore steps;
    protected TotpCredentialStore credentials;

    protected abstract TotpStepStore newStepStore();

    protected abstract TotpCredentialStore newCredentialStore();

    @BeforeEach
    public void createStores() {
        steps = newStepStore();
        credentials = newCredentialStore();
    }

    @Test
    @DisplayName("SSO-T-contract-080 TotpStepStore.advance accepts a step once under contention and never moves backwards")
    public void advanceOnce() throws Exception {
        String user = Race.id("u");
        assertEquals(1, Race.count(Race.run(Race.THREADS, () -> steps.advance(user, 1000))));
        assertEquals(1000, steps.lastStep(user));
        assertFalse(steps.advance(user, 999));
        assertTrue(steps.advance(user, 1001));
        steps.clear(user);
        assertTrue(steps.advance(user, 5));
    }

    @Test
    @DisplayName("SSO-T-contract-081 TotpCredentialStore never lets an unconfirmed credential replace a confirmed one")
    public void confirmedIsNotReplaced() {
        String user = Race.id("u");
        credentials.save(new TotpCredential(user, Bytes.random(40), TotpConfig.defaults(), false, Race.now(), null));
        credentials.save(new TotpCredential(user, Bytes.random(40), TotpConfig.defaults(), false, Race.now(), null));
        byte[] sealed = Bytes.random(40);
        credentials.save(new TotpCredential(user, sealed, TotpConfig.defaults(), true, Race.now(), Race.now()));
        assertThrows(SsoException.class, () -> credentials.save(new TotpCredential(user, Bytes.random(40), TotpConfig.defaults(), false, Race.now(), null)));
        TotpCredential kept = credentials.find(user).orElseThrow();
        assertTrue(kept.confirmed());
        assertTrue(java.util.Arrays.equals(sealed, kept.sealedSecret()));
        credentials.delete(user);
        credentials.save(new TotpCredential(user, Bytes.random(40), TotpConfig.defaults(), false, Race.now(), null));
        assertFalse(credentials.find(user).orElseThrow().confirmed());
    }

    @Test
    @DisplayName("SSO-T-contract-082 TotpCredentialStore.confirm confirms only the unconfirmed credential holding the expected secret, once, and never creates one")
    public void confirmCompareAndSwap() throws Exception {
        String user = Race.id("u");
        byte[] first = Bytes.random(40);
        assertFalse(credentials.confirm(user, first, Race.now()));
        assertTrue(credentials.find(user).isEmpty());
        credentials.save(new TotpCredential(user, first, TotpConfig.defaults(), false, Race.now(), null));
        byte[] second = Bytes.random(40);
        credentials.save(new TotpCredential(user, second, TotpConfig.defaults(), false, Race.now(), null));
        assertFalse(credentials.confirm(user, first, Race.now()));
        assertFalse(credentials.find(user).orElseThrow().confirmed());
        assertEquals(1, Race.count(Race.run(Race.THREADS, () -> credentials.confirm(user, second, Race.now()))));
        TotpCredential confirmed = credentials.find(user).orElseThrow();
        assertTrue(confirmed.confirmed());
        assertTrue(java.util.Arrays.equals(second, confirmed.sealedSecret()));
        assertTrue(confirmed.confirmedAt() != null);
    }

    @Test
    @DisplayName("SSO-T-contract-083 TotpStepStore.advance with distinct racing steps accepts each step at most once and ends on the highest")
    public void advanceDistinctSteps() throws Exception {
        String user = Race.id("u");
        assertTrue(steps.advance(user, 1));
        AtomicInteger n = new AtomicInteger();
        ConcurrentHashMap<Long, AtomicInteger> accepted = new ConcurrentHashMap<>();
        Race.run(Race.THREADS, () -> {
            long step = 1000 + n.getAndIncrement() % 4;
            if (steps.advance(user, step)) {
                accepted.computeIfAbsent(step, k -> new AtomicInteger()).incrementAndGet();
            }
            return null;
        });
        accepted.values().forEach(c -> assertEquals(1, c.get()));
        assertEquals(1, accepted.get(1003L).get());
        assertEquals(1003, steps.lastStep(user));
        assertFalse(steps.advance(user, 1003));
    }

    @Test
    @DisplayName("SSO-T-contract-084 TotpCredentialStore.confirm racing new enrollments confirms only the secret still stored, never a replaced one")
    public void confirmRacingEnrollment() throws Exception {
        String user = Race.id("u");
        byte[] first = Bytes.random(40);
        credentials.save(new TotpCredential(user, first, TotpConfig.defaults(), false, Race.now(), null));
        AtomicInteger n = new AtomicInteger();
        List<Boolean> confirms = Race.run(Race.THREADS, () -> {
            if (n.getAndIncrement() % 2 == 0) {
                return credentials.confirm(user, first, Race.now());
            }
            try {
                credentials.save(new TotpCredential(user, Bytes.random(40), TotpConfig.defaults(), false, Race.now(), null));
            } catch (SsoException e) {
                return false;
            }
            return false;
        });
        long wins = Race.count(confirms);
        TotpCredential kept = credentials.find(user).orElseThrow();
        assertTrue(wins <= 1);
        assertEquals(wins == 1, kept.confirmed());
        if (wins == 1) {
            assertTrue(java.util.Arrays.equals(first, kept.sealedSecret()));
        }
    }
}
