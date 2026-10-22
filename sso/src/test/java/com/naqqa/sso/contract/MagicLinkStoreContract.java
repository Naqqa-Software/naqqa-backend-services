package com.naqqa.sso.contract;

import com.naqqa.sso.otp.MagicLinkRecord;
import com.naqqa.sso.otp.MagicLinkStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public abstract class MagicLinkStoreContract {
    protected MagicLinkStore store;

    protected abstract MagicLinkStore newStore();

    @BeforeEach
    public void createStore() {
        store = newStore();
    }

    @Test
    @DisplayName("SSO-T-contract-040 MagicLinkStore.markUsed succeeds once under contention")
    public void markUsedOnce() throws Exception {
        String hash = Race.id("ml");
        store.save(new MagicLinkRecord(hash, Race.id("u"), "/home", null, Race.now().plusSeconds(600), false));
        assertEquals(1, Race.count(Race.run(Race.THREADS, () -> store.markUsed(hash))));
        assertTrue(store.find(hash).orElseThrow().used());
        assertFalse(store.markUsed(Race.id("missing")));
    }
}
