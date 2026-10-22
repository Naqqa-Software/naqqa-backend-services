package com.naqqa.sso.contract;

import com.naqqa.sso.mfa.RecoverySet;
import com.naqqa.sso.mfa.RecoveryStore;
import com.naqqa.sso.util.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public abstract class RecoveryStoreContract {
    protected RecoveryStore store;

    protected abstract RecoveryStore newStore();

    @BeforeEach
    public void createStore() {
        store = newStore();
    }

    @Test
    @DisplayName("SSO-T-contract-090 RecoveryStore.markUsed marks a code once under contention and resets the failure counter")
    public void markUsedOnce() throws Exception {
        String user = Race.id("u");
        byte[] h0 = Bytes.random(32);
        byte[] h1 = Bytes.random(32);
        store.save(new RecoverySet(user, List.of(new RecoverySet.Entry(Bytes.random(16), h0, false, Bytes.random(16)),
                new RecoverySet.Entry(Bytes.random(16), h1, false, Bytes.random(16))), 0, null));
        store.recordFailure(user, 3, Race.now());
        assertEquals(1, Race.count(Race.run(Race.THREADS, () -> store.markUsed(user, h1))));
        RecoverySet s = store.find(user).orElseThrow();
        assertFalse(s.entries().get(0).used());
        assertTrue(s.entries().get(1).used());
        assertEquals(0, s.failedAttempts());
        assertNull(s.lastFailure());
        assertFalse(store.markUsed(user, Bytes.random(32)));
        assertFalse(store.markUsed(Race.id("missing"), h0));
    }

    @Test
    @DisplayName("SSO-T-contract-091 RecoveryStore.recordFailure never rewrites the codes, so it cannot resurrect a used one, and never creates a set")
    public void failureKeepsCodes() {
        String user = Race.id("u");
        byte[] h0 = Bytes.random(32);
        store.save(new RecoverySet(user, List.of(new RecoverySet.Entry(Bytes.random(16), h0, false, Bytes.random(16))), 0, null));
        assertTrue(store.markUsed(user, h0));
        store.recordFailure(user, 2, Race.now());
        RecoverySet s = store.find(user).orElseThrow();
        assertTrue(s.entries().get(0).used());
        assertEquals(2, s.failedAttempts());
        String none = Race.id("u");
        store.recordFailure(none, 1, Race.now());
        assertTrue(store.find(none).isEmpty());
    }

    @Test
    @DisplayName("SSO-T-contract-092 RecoveryStore.save replaces the whole set, so codes of a replaced set no longer redeem")
    public void regenerateReplaces() {
        String user = Race.id("u");
        byte[] old = Bytes.random(32);
        store.save(new RecoverySet(user, List.of(new RecoverySet.Entry(Bytes.random(16), old, false, Bytes.random(16))), 0, null));
        store.save(new RecoverySet(user, List.of(new RecoverySet.Entry(Bytes.random(16), Bytes.random(32), false, Bytes.random(16))), 0, null));
        assertFalse(store.markUsed(user, old));
        store.delete(user);
        assertTrue(store.find(user).isEmpty());
    }

    @Test
    @DisplayName("SSO-T-contract-093 RecoveryStore.markUsed of distinct codes in parallel loses no update: every code is used once")
    public void markUsedDistinctCodes() throws Exception {
        String user = Race.id("u");
        List<byte[]> hashes = new ArrayList<>();
        List<RecoverySet.Entry> entries = new ArrayList<>();
        for (int i = 0; i < Race.THREADS; i++) {
            byte[] h = Bytes.random(32);
            hashes.add(h);
            entries.add(new RecoverySet.Entry(Bytes.random(16), h, false, Bytes.random(16)));
        }
        store.save(new RecoverySet(user, entries, 0, null));
        AtomicInteger n = new AtomicInteger();
        assertEquals(Race.THREADS, Race.count(Race.run(Race.THREADS, () -> store.markUsed(user, hashes.get(n.getAndIncrement())))));
        RecoverySet s = store.find(user).orElseThrow();
        assertTrue(s.entries().stream().allMatch(RecoverySet.Entry::used));
        for (byte[] h : hashes) {
            assertFalse(store.markUsed(user, h));
        }
    }
}
