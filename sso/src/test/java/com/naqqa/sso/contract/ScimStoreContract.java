package com.naqqa.sso.contract;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.scim.ScimGroupStore;
import com.naqqa.sso.scim.ScimResource;
import com.naqqa.sso.scim.ScimUserStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public abstract class ScimStoreContract {
    protected ScimUserStore store;
    protected ScimGroupStore groups;

    protected abstract ScimUserStore newUserStore();

    protected abstract ScimGroupStore newGroupStore();

    @BeforeEach
    public void createStore() {
        store = newUserStore();
        groups = newGroupStore();
    }

    private static ScimResource user(String tenant, String id, String userName) {
        Instant now = Race.now();
        return new ScimResource(tenant, id, Map.of("userName", userName), 1, now, now);
    }

    @Test
    @DisplayName("SSO-T-contract-120 ScimResourceStore.insert enforces a case-insensitive unique userName per tenant under contention")
    public void insertUnique() throws Exception {
        String tenant = Race.id("t");
        List<Boolean> inserted = Race.run(Race.THREADS, () -> {
            try {
                store.insert(user(tenant, UUID.randomUUID().toString(), Thread.currentThread().getId() % 2 == 0 ? "Ana@Corp.test" : "ana@corp.test"));
                return true;
            } catch (SsoException e) {
                return false;
            }
        });
        assertEquals(1, Race.count(inserted));
        store.insert(user(Race.id("t"), UUID.randomUUID().toString(), "ana@corp.test"));
        assertTrue(store.findByUserName(tenant, "ANA@corp.test").isPresent());
    }

    @Test
    @DisplayName("SSO-T-contract-121 ScimResourceStore.update is a compare-and-swap on the version: one of many racing updates wins")
    public void updateCompareAndSwap() throws Exception {
        String tenant = Race.id("t");
        ScimResource r = store.insert(user(tenant, UUID.randomUUID().toString(), Race.id("user")));
        List<Boolean> wins = Race.run(Race.THREADS, () -> {
            try {
                store.update(r.withAttributes(Map.of("userName", r.attributes().get("userName"), "title", Race.id("x")), Race.now()), 1);
                return true;
            } catch (SsoException e) {
                return false;
            }
        });
        assertEquals(1, Race.count(wins));
        assertEquals(2, store.find(tenant, r.id()).orElseThrow().version());
        assertEquals("scim.version_conflict", assertThrows(SsoException.class,
                () -> store.update(r.withAttributes(Map.of("userName", "late"), Race.now()), 1)).code());
        assertEquals(1, Race.count(Race.run(Race.THREADS, () -> store.delete(tenant, r.id()))));
        assertEquals("scim.not_found", assertThrows(SsoException.class,
                () -> store.update(r.withAttributes(Map.of("userName", "gone"), Race.now()), 1)).code());
        assertFalse(store.find(tenant, r.id()).isPresent());
    }

    @Test
    @DisplayName("SSO-T-contract-122 ScimGroupStore.insert enforces a case-insensitive unique displayName per tenant under contention; delete reports one removal")
    public void groupDisplayNameUnique() throws Exception {
        String tenant = Race.id("t");
        List<Boolean> inserted = Race.run(Race.THREADS, () -> {
            Instant now = Race.now();
            try {
                groups.insert(new ScimResource(tenant, UUID.randomUUID().toString(),
                        Map.of("displayName", Thread.currentThread().getId() % 2 == 0 ? "Admins" : "ADMINS"), 1, now, now));
                return true;
            } catch (SsoException e) {
                return false;
            }
        });
        assertEquals(1, Race.count(inserted));
        ScimResource g = groups.findByDisplayName(tenant, "admins").orElseThrow();
        assertEquals(1, Race.count(Race.run(Race.THREADS, () -> groups.delete(tenant, g.id()))));
        assertTrue(groups.findByDisplayName(tenant, "admins").isEmpty());
    }
}
