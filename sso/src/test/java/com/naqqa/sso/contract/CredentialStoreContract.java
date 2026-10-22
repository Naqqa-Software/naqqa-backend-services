package com.naqqa.sso.contract;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.webauthn.AttestationType;
import com.naqqa.sso.webauthn.CoseAlgorithm;
import com.naqqa.sso.webauthn.CredentialRecord;
import com.naqqa.sso.webauthn.CredentialStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public abstract class CredentialStoreContract {
    protected CredentialStore store;

    protected abstract CredentialStore newStore();

    @BeforeEach
    public void createStore() {
        store = newStore();
    }

    private static CredentialRecord credential(byte[] id, String userId, long signCount) {
        return new CredentialRecord(id, Bytes.random(16), userId, Bytes.random(77), CoseAlgorithm.ES256, signCount, true, true, false,
                List.of("internal"), Bytes.random(16), "none", AttestationType.NONE, false, Race.now(), null, false);
    }

    private static CredentialRecord used(CredentialRecord r, long signCount) {
        return new CredentialRecord(r.credentialId(), r.userHandle(), r.userId(), r.publicKeyCose(), r.algorithm(), signCount,
                r.uvInitialized(), r.backupEligible(), r.backupState(), r.transports(), r.aaguid(), r.attestationFormat(),
                r.attestationType(), r.attestationTrusted(), r.createdAt(), Race.now(), r.cloneDetected());
    }

    @Test
    @DisplayName("SSO-T-contract-070 CredentialStore.create is unique by credential id, also under contention")
    public void createUnique() throws Exception {
        byte[] id = Bytes.random(32);
        String user = Race.id("u");
        List<Boolean> created = Race.run(Race.THREADS, () -> {
            try {
                store.create(credential(id, user, 0));
                return true;
            } catch (SsoException e) {
                return false;
            }
        });
        assertEquals(1, Race.count(created));
        assertThrows(SsoException.class, () -> store.create(credential(id, user, 0)));
        assertEquals(1, store.findByUserId(user).size());
    }

    @Test
    @DisplayName("SSO-T-contract-071 CredentialStore.update is a compare-and-swap on the previous record: one of many racing assertions wins")
    public void updateCompareAndSwap() throws Exception {
        byte[] id = Bytes.random(32);
        CredentialRecord start = credential(id, Race.id("u"), 5);
        store.create(start);
        CredentialRecord previous = store.findById(id).orElseThrow();
        AtomicInteger n = new AtomicInteger(5);
        assertEquals(1, Race.count(Race.run(Race.THREADS, () -> store.update(previous, used(previous, n.incrementAndGet())))));
        assertTrue(store.findById(id).orElseThrow().signCount() > 5);
        assertFalse(store.update(previous, used(previous, 100)));
        assertTrue(store.delete(id));
        assertFalse(store.delete(id));
    }

    @Test
    @DisplayName("SSO-T-contract-072 CredentialStore.delete reports one removal under contention")
    public void deleteOnce() throws Exception {
        byte[] id = Bytes.random(32);
        String user = Race.id("u");
        store.create(credential(id, user, 0));
        assertEquals(1, Race.count(Race.run(Race.THREADS, () -> store.delete(id))));
        assertTrue(store.findById(id).isEmpty());
        assertTrue(store.findByUserId(user).isEmpty());
    }
}
