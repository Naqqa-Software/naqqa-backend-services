package com.naqqa.sso.contract;

import com.naqqa.sso.audit.AuditEntry;
import com.naqqa.sso.audit.AuditEvent;
import com.naqqa.sso.audit.AuditStore;
import com.naqqa.sso.oidc.ClientRegistration;
import com.naqqa.sso.oidc.ClientStore;
import com.naqqa.sso.oidc.ConsentRecord;
import com.naqqa.sso.oidc.ConsentStore;
import com.naqqa.sso.ratelimit.FailureState;
import com.naqqa.sso.ratelimit.RateLimitStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public abstract class LedgerStoresContract {
    protected AuditStore audit;
    protected RateLimitStore limits;
    protected ConsentStore consents;
    protected ClientStore clients;

    protected abstract AuditStore newAuditStore();

    protected abstract RateLimitStore newRateLimitStore();

    protected abstract ConsentStore newConsentStore();

    protected abstract ClientStore newClientStore();

    @BeforeEach
    public void createStores() {
        audit = newAuditStore();
        limits = newRateLimitStore();
        consents = newConsentStore();
        clients = newClientStore();
    }

    @Test
    @DisplayName("SSO-T-contract-130 AuditStore.append rejects a second entry with a sequence number already taken, so two writers cannot fork the chain")
    public void auditSequenceUnique() throws Exception {
        long base = audit.all().stream().mapToLong(AuditEntry::seq).max().orElse(0) + 1;
        AuditEvent event = new AuditEvent(Race.now(), "t", "a", "act", "x", "success", null, Map.of());
        List<Boolean> appended = Race.run(Race.THREADS, () -> {
            try {
                audit.append(new AuditEntry(base, event, "prev", Race.id("h"), "k", "sig"));
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        });
        assertEquals(1, Race.count(appended));
        audit.append(new AuditEntry(base + 1, event, "prev", "h2", "k", "sig"));
        assertThrows(RuntimeException.class, () -> audit.append(new AuditEntry(base + 1, event, "prev", "h3", "k", "sig")));
        List<AuditEntry> all = audit.all();
        assertEquals(base + 1, all.get(all.size() - 1).seq());
        assertEquals(base, all.get(all.size() - 2).seq());
    }

    @Test
    @DisplayName("SSO-T-contract-131 RateLimitStore.atomically serializes read-modify-write: 16 parallel failures are all counted")
    public void rateLimitAtomic() throws Exception {
        String key = Race.id("lt");
        Race.run(Race.THREADS, () -> limits.atomically(() -> {
            FailureState f = limits.failures(key);
            limits.putFailures(key, new FailureState(f == null ? 1 : f.count() + 1, System.currentTimeMillis()));
            return null;
        }));
        assertEquals(Race.THREADS, limits.failures(key).count());
    }

    @Test
    @DisplayName("SSO-T-contract-132 ConsentStore.revoke reports one removal under contention")
    public void consentRevokeOnce() throws Exception {
        String user = Race.id("u");
        consents.save(new ConsentRecord("t", user, "c", Set.of("openid"), Race.now()));
        assertEquals(1, Race.count(Race.run(Race.THREADS, () -> consents.revoke("t", user, "c"))));
        assertEquals(0, consents.listByUser("t", user).size());
    }

    @Test
    @DisplayName("SSO-T-contract-133 ClientStore.save replaces by client id and delete reports one removal under contention")
    public void clientDeleteOnce() throws Exception {
        String id = Race.id("client");
        clients.save(ClientRegistration.builder("t", id).name("first").publicClient().redirectUris("https://app.example.com/cb").build());
        clients.save(ClientRegistration.builder("t", id).name("second").publicClient().redirectUris("https://app.example.com/cb").build());
        assertEquals("second", clients.find(id).orElseThrow().clientName());
        assertEquals(1, Race.count(Race.run(Race.THREADS, () -> clients.delete(id))));
        assertTrue(clients.find(id).isEmpty());
    }

    @Test
    @DisplayName("SSO-T-contract-134 ConsentStore.grant adds scopes atomically: parallel grants of different scopes all survive, a grant after a revoke starts from nothing")
    public void consentGrantUnion() throws Exception {
        String user = Race.id("u");
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        Race.run(Race.THREADS, () -> {
            consents.grant("t", user, "c", Set.of("openid", "s" + n.getAndIncrement()), Race.now());
            return null;
        });
        assertEquals(Race.THREADS + 1, consents.find("t", user, "c").orElseThrow().scopes().size());
        assertTrue(consents.revoke("t", user, "c"));
        consents.grant("t", user, "c", Set.of("openid"), Race.now());
        assertEquals(Set.of("openid"), consents.find("t", user, "c").orElseThrow().scopes());
    }
}
