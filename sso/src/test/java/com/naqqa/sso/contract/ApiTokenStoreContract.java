package com.naqqa.sso.contract;

import com.naqqa.sso.tokens.ApiTokenEnv;
import com.naqqa.sso.tokens.ApiTokenKind;
import com.naqqa.sso.tokens.ApiTokenRecord;
import com.naqqa.sso.tokens.ApiTokenStore;
import com.naqqa.sso.tokens.Cidr;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public abstract class ApiTokenStoreContract {
    protected ApiTokenStore store;

    protected abstract ApiTokenStore newStore();

    @BeforeEach
    public void createStore() {
        store = newStore();
    }

    private ApiTokenRecord token(String id) {
        return new ApiTokenRecord(id, "tenant-" + id, ApiTokenKind.SECRET, ApiTokenEnv.LIVE, "n", Set.of("a"), List.of(), "h1-" + id,
                null, null, Race.now(), null, null, 0, null);
    }

    @Test
    @DisplayName("SSO-T-contract-001 ApiTokenStore.recordFailure is an atomic increment: 16 parallel failures count 16 with distinct results")
    public void failureCounterAtomic() throws Exception {
        String id = Race.id("tok");
        store.save(token(id));
        List<Integer> results = Race.run(Race.THREADS, () -> store.recordFailure(id));
        assertEquals(Race.THREADS, new HashSet<>(results).size());
        assertEquals(Race.THREADS, store.findById(id).orElseThrow().failedAttempts());
        store.resetFailures(id);
        assertEquals(0, store.findById(id).orElseThrow().failedAttempts());
        assertEquals(0, store.recordFailure(Race.id("missing")));
    }

    @Test
    @DisplayName("SSO-T-contract-002 ApiTokenStore.revoke flips once under contention and revocation is sticky against stale saves")
    public void revokeOnceAndSticky() throws Exception {
        String id = Race.id("tok");
        ApiTokenRecord stale = token(id);
        store.save(stale);
        assertEquals(1, Race.count(Race.run(Race.THREADS, () -> store.revoke(id, Race.now()))));
        store.save(stale.withFailures(3, null));
        assertTrue(store.findById(id).orElseThrow().revoked());
        assertFalse(store.revoke(Race.id("missing"), Race.now()));
    }

    @Test
    @DisplayName("SSO-T-contract-003 ApiTokenStore.rotate is a compare-and-swap on the current hash and refuses a revoked token")
    public void rotateCompareAndSwap() throws Exception {
        String id = Race.id("tok");
        store.save(token(id));
        AtomicInteger n = new AtomicInteger();
        List<Boolean> wins = Race.run(Race.THREADS, () -> store.rotate(id, "h1-" + id, "h2-" + id + "-" + n.incrementAndGet(), "h1-" + id, Race.now().plusSeconds(60), Race.now()));
        assertEquals(1, Race.count(wins));
        ApiTokenRecord r = store.findById(id).orElseThrow();
        assertTrue(r.hash().startsWith("h2-"));
        assertEquals("h1-" + id, r.previousHash());
        assertNotNull(r.rotatedAt());
        assertTrue(store.revoke(id, Race.now()));
        assertFalse(store.rotate(id, r.hash(), "h3-" + id, null, null, Race.now()));
        assertEquals(r.hash(), store.findById(id).orElseThrow().hash());
    }

    @Test
    @DisplayName("SSO-T-contract-004 ApiTokenStore scope and allowlist updates touch only their field")
    public void fieldUpdates() {
        String id = Race.id("tok");
        store.save(token(id));
        store.recordFailure(id);
        assertTrue(store.updateScopes(id, Set.of("b", "c")));
        assertTrue(store.updateAllowlist(id, List.of(Cidr.parse("10.0.0.0/8"))));
        ApiTokenRecord r = store.findById(id).orElseThrow();
        assertEquals(Set.of("b", "c"), r.scopes());
        assertEquals(1, r.allowlist().size());
        assertEquals(1, r.failedAttempts());
        assertEquals("h1-" + id, r.hash());
        assertFalse(store.updateScopes(Race.id("missing"), Set.of("x")));
        assertFalse(store.updateAllowlist(Race.id("missing"), List.of()));
    }

    @Test
    @DisplayName("SSO-T-contract-005 ApiTokenStore.delete reports one removal under contention")
    public void deleteOnce() throws Exception {
        String id = Race.id("tok");
        store.save(token(id));
        assertEquals(1, Race.count(Race.run(Race.THREADS, () -> store.delete(id))));
        assertTrue(store.findById(id).isEmpty());
    }
}
