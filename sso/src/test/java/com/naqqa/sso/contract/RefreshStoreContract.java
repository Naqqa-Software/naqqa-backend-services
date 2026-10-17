package com.naqqa.sso.contract;

import com.naqqa.sso.session.RefreshRecord;
import com.naqqa.sso.session.RefreshStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public abstract class RefreshStoreContract {
    protected RefreshStore store;

    protected abstract RefreshStore newStore();

    @BeforeEach
    public void createStore() {
        store = newStore();
    }

    @Test
    @DisplayName("SSO-T-contract-020 RefreshStore.markRotated succeeds once under contention")
    public void rotateOnce() throws Exception {
        String hash = Race.id("rt");
        store.save(new RefreshRecord(hash, Race.id("fam"), Race.id("u"), "c", Race.now(), Race.now().plusSeconds(3600), false));
        assertEquals(1, Race.count(Race.run(Race.THREADS, () -> store.markRotated(hash))));
        assertTrue(store.find(hash).orElseThrow().rotated());
        assertFalse(store.markRotated(Race.id("missing")));
    }

    @Test
    @DisplayName("SSO-T-contract-021 RefreshStore family revocation is visible at once and revokeAllForUser covers every family of the user only")
    public void familyRevocation() {
        String user = Race.id("u");
        String other = Race.id("u");
        String f1 = Race.id("fam");
        String f2 = Race.id("fam");
        String f3 = Race.id("fam");
        store.save(new RefreshRecord(Race.id("rt"), f1, user, "c", Race.now(), Race.now().plusSeconds(3600), false));
        store.save(new RefreshRecord(Race.id("rt"), f2, user, "c", Race.now(), Race.now().plusSeconds(3600), false));
        store.save(new RefreshRecord(Race.id("rt"), f3, other, "c", Race.now(), Race.now().plusSeconds(3600), false));
        store.revokeFamily(f1);
        assertTrue(store.isFamilyRevoked(f1));
        assertFalse(store.isFamilyRevoked(f2));
        store.revokeAllForUser(user);
        assertTrue(store.isFamilyRevoked(f2));
        assertFalse(store.isFamilyRevoked(f3));
    }
}
