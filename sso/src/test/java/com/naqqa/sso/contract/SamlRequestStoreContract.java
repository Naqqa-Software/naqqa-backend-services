package com.naqqa.sso.contract;

import com.naqqa.sso.saml.sp.SamlRequestStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

public abstract class SamlRequestStoreContract {
    protected SamlRequestStore requests;

    protected abstract SamlRequestStore newStore();

    @BeforeEach
    public void createStore() {
        requests = newStore();
    }

    @Test
    @DisplayName("SSO-T-contract-102 SamlRequestStore.consume accepts a live request id once under contention and never an expired one")
    public void consumeOnce() throws Exception {
        String id = Race.id("req");
        requests.save(id, Race.now().plusSeconds(300));
        assertEquals(1, Race.count(Race.run(Race.THREADS, () -> requests.consume(id))));
        String expired = Race.id("req");
        requests.save(expired, Race.now().minusSeconds(1));
        assertFalse(requests.consume(expired));
    }
}
