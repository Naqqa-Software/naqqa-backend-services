package com.naqqa.sso.contract;

import com.naqqa.sso.otp.PushApproval;
import com.naqqa.sso.otp.PushApprovalRecord;
import com.naqqa.sso.otp.PushApprovalStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public abstract class PushApprovalStoreContract {
    protected PushApprovalStore store;

    protected abstract PushApprovalStore newStore();

    @BeforeEach
    public void createStore() {
        store = newStore();
    }

    private String pending() {
        String id = Race.id("push");
        store.create(new PushApprovalRecord(id, Race.id("u"), "42", Race.now().plusSeconds(120), PushApproval.Status.PENDING, 0));
        return id;
    }

    @Test
    @DisplayName("SSO-T-contract-050 PushApprovalStore.transition is a compare-and-swap: one of many racing transitions from PENDING wins")
    public void transitionOnce() throws Exception {
        String id = pending();
        List<Boolean> wins = Race.run(Race.THREADS, () -> Thread.currentThread().getId() % 2 == 0
                ? store.transition(id, PushApproval.Status.PENDING, PushApproval.Status.APPROVED)
                : store.transition(id, PushApproval.Status.PENDING, PushApproval.Status.DENIED));
        assertEquals(1, Race.count(wins));
        PushApproval.Status decided = store.find(id).orElseThrow().status();
        assertEquals(1, Race.count(Race.run(Race.THREADS, () -> store.transition(id, decided, PushApproval.Status.CONSUMED))));
        assertEquals(PushApproval.Status.CONSUMED, store.find(id).orElseThrow().status());
        assertFalse(store.transition(Race.id("missing"), PushApproval.Status.PENDING, PushApproval.Status.APPROVED));
    }

    @Test
    @DisplayName("SSO-T-contract-051 PushApprovalStore.addMismatch is an atomic increment on a pending approval only")
    public void mismatchCounter() throws Exception {
        String id = pending();
        List<OptionalInt> counts = Race.run(Race.THREADS, () -> store.addMismatch(id));
        HashSet<Integer> distinct = new HashSet<>();
        counts.forEach(c -> distinct.add(c.orElse(-1)));
        assertEquals(Race.THREADS, distinct.size());
        assertEquals(Race.THREADS, store.find(id).orElseThrow().mismatches());
        assertTrue(store.transition(id, PushApproval.Status.PENDING, PushApproval.Status.DENIED));
        assertTrue(store.addMismatch(id).isEmpty());
    }

    @Test
    @DisplayName("SSO-T-contract-052 PushApprovalStore.create refuses a duplicate id under contention and keeps the first record")
    public void createOnce() throws Exception {
        String id = Race.id("push");
        String user = Race.id("u");
        AtomicInteger n = new AtomicInteger();
        List<Boolean> created = Race.run(Race.THREADS, () -> {
            try {
                store.create(new PushApprovalRecord(id, user, Integer.toString(10 + n.incrementAndGet()), Race.now().plusSeconds(120),
                        PushApproval.Status.PENDING, 0));
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        });
        assertEquals(1, Race.count(created));
        PushApprovalRecord kept = store.find(id).orElseThrow();
        assertEquals(PushApproval.Status.PENDING, kept.status());
        assertEquals(user, kept.userId());
    }
}
