package com.naqqa.sso.contract;

import com.naqqa.sso.oauth.StateRecord;
import com.naqqa.sso.oauth.StateStore;
import com.naqqa.sso.saml.ReplayCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public abstract class SingleUseStoresContract {
    protected StateStore states;
    protected ReplayCache replay;

    protected abstract StateStore newStateStore();

    protected abstract ReplayCache newReplayCache();

    @BeforeEach
    public void createStores() {
        states = newStateStore();
        replay = newReplayCache();
    }

    @Test
    @DisplayName("SSO-T-contract-100 StateStore.consume returns a state once under contention")
    public void stateOnce() throws Exception {
        String hash = Race.id("st");
        states.put(new StateRecord(hash, "google", "nonce", "verifier", "binding", "/", Race.now().plusSeconds(600)));
        assertEquals(1, Race.count(Race.run(Race.THREADS, () -> states.consume(hash).isPresent())));
        assertTrue(states.consume(hash).isEmpty());
    }

    @Test
    @DisplayName("SSO-T-contract-101 ReplayCache.markIfAbsent accepts a live key once under contention")
    public void replayOnce() throws Exception {
        String key = Race.id("assertion");
        assertEquals(1, Race.count(Race.run(Race.THREADS, () -> replay.markIfAbsent(key, Race.now().plusSeconds(300)))));
    }

    @Test
    @DisplayName("SSO-T-contract-103 ReplayCache.markIfAbsent re-accepts an expired key once under contention")
    public void replayExpiredOnce() throws Exception {
        String key = Race.id("assertion");
        assertTrue(replay.markIfAbsent(key, Race.now().minusSeconds(5)));
        assertEquals(1, Race.count(Race.run(Race.THREADS, () -> replay.markIfAbsent(key, Race.now().plusSeconds(300)))));
        assertFalse(replay.markIfAbsent(key, Race.now().plusSeconds(300)));
    }
}
