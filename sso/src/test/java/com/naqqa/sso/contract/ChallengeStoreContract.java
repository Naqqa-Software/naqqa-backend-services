package com.naqqa.sso.contract;

import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.webauthn.Ceremony;
import com.naqqa.sso.webauthn.ChallengeStore;
import com.naqqa.sso.webauthn.WebAuthnChallenge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public abstract class ChallengeStoreContract {
    protected ChallengeStore store;

    protected abstract ChallengeStore newStore();

    @BeforeEach
    public void createStore() {
        store = newStore();
    }

    @Test
    @DisplayName("SSO-T-contract-060 ChallengeStore.consume returns a challenge once under contention, never an expired one")
    public void consumeOnce() throws Exception {
        byte[] value = Bytes.random(32);
        store.save(new WebAuthnChallenge(value, Ceremony.AUTHENTICATION, null, "u", "n", "d", null, Race.now().plusSeconds(60)));
        assertEquals(1, Race.count(Race.run(Race.THREADS, () -> store.consume(value).isPresent())));
        assertTrue(store.consume(value).isEmpty());
        byte[] expired = Bytes.random(32);
        store.save(new WebAuthnChallenge(expired, Ceremony.REGISTRATION, Bytes.random(16), "u", "n", "d", null, Race.now().minusSeconds(1)));
        assertTrue(store.consume(expired).isEmpty());
        assertTrue(store.consume(null).isEmpty());
    }
}
