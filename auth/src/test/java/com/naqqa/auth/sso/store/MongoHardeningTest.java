package com.naqqa.auth.sso.store;

import com.naqqa.sso.crypto.SecretBox;
import com.naqqa.sso.mfa.RecoveryCodes;
import com.naqqa.sso.mfa.RecoverySet;
import com.naqqa.sso.otp.NoopTransport;
import com.naqqa.sso.otp.PushApproval;
import com.naqqa.sso.otp.PushApprovalRecord;
import com.naqqa.sso.ratelimit.FailureState;
import com.naqqa.sso.util.Bytes;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MongoHardeningTest extends MongoStoreTestBase {

    @Test
    @DisplayName("SSO-T-auth-store-017 F-A1 rate limit counts exactly with 50 threads on one key without a global lock")
    void rateLimitConcurrent() throws Exception {
        MongoRateLimitStore store = new MongoRateLimitStore(template, CLOCK, Duration.ofDays(1));
        race(50, () -> store.atomically(() -> {
            FailureState f = store.failures("hot");
            store.putFailures("hot", new FailureState(f == null ? 1 : f.count() + 1, 0));
            return null;
        }));
        assertEquals(50, store.failures("hot").count());
        assertEquals(0, template.getCollection("sso_locks").countDocuments());
    }

    @Test
    @DisplayName("SSO-T-auth-store-018 F-A1 different keys do not contend and documents carry a TTL expiry")
    void rateLimitIndependentKeys() throws Exception {
        MongoRateLimitStore store = new MongoRateLimitStore(template, CLOCK, Duration.ofDays(1));
        AtomicInteger n = new AtomicInteger();
        race(20, () -> store.atomically(() -> {
            String key = "k" + n.incrementAndGet();
            store.putFailures(key, new FailureState(1, 0));
            return null;
        }));
        for (int i = 1; i <= 20; i++) {
            assertEquals(1, store.failures("k" + i).count());
        }
        Document d = template.findById("k1", Document.class, MongoRateLimitStore.COLLECTION);
        assertNotNull(d.getDate("expiresAt"));
        assertTrue(d.getDate("expiresAt").toInstant().isAfter(Instant.now().plus(Duration.ofHours(23))));
    }

    @Test
    @DisplayName("SSO-T-auth-store-019 F-A1 remove inside and outside atomically clears the key")
    void rateLimitRemove() {
        MongoRateLimitStore store = new MongoRateLimitStore(template, CLOCK, Duration.ofDays(1));
        store.putFailures("r", new FailureState(3, 1));
        store.atomically(() -> {
            store.remove("r");
            return null;
        });
        assertEquals(null, store.failures("r"));
        store.putFailures("r", new FailureState(2, 1));
        store.remove("r");
        assertEquals(null, store.failures("r"));
    }

    @Test
    @DisplayName("SSO-T-auth-store-020 F-A2 push approval transitions are atomic across 30 racing deciders")
    void pushTransitions() throws Exception {
        MongoPushApprovalStore store = new MongoPushApprovalStore(template);
        Instant exp = Instant.now().plus(Duration.ofMinutes(2));
        store.create(new PushApprovalRecord("p1", "u1", "42", exp.truncatedTo(java.time.temporal.ChronoUnit.MILLIS), PushApproval.Status.PENDING, 0));
        AtomicInteger winners = new AtomicInteger();
        AtomicInteger counter = new AtomicInteger();
        race(30, () -> {
            PushApproval.Status to = counter.incrementAndGet() % 2 == 0 ? PushApproval.Status.APPROVED : PushApproval.Status.DENIED;
            if (store.transition("p1", PushApproval.Status.PENDING, to)) {
                winners.incrementAndGet();
            }
            return null;
        });
        assertEquals(1, winners.get());
        assertFalse(store.addMismatch("p1").isPresent());
    }

    @Test
    @DisplayName("SSO-T-auth-store-021 F-A2 PushApproval over the Mongo store approves once and consumes once")
    void pushService() throws Exception {
        MongoPushApprovalStore store = new MongoPushApprovalStore(template);
        PushApproval push = new PushApproval(CLOCK, new NoopTransport(), store);
        PushApproval.Challenge c = push.create("u9", "device");
        assertEquals(PushApproval.Status.PENDING, push.status(c.id()));
        assertEquals(PushApproval.Status.APPROVED, push.respond(c.id(), "u9", c.displayNumber(), true));
        List<Boolean> consumed = race(10, () -> push.consume(c.id(), "u9"));
        assertEquals(1, consumed.stream().filter(b -> b).count());
        assertTrue(template.indexOps(MongoPushApprovalStore.COLLECTION).getIndexInfo().stream().anyMatch(i -> i.getExpireAfter().isPresent()));
    }

    @Test
    @DisplayName("SSO-T-auth-store-022 F-T2 sealed counter survives a crash and a restart never reuses counters")
    void counterSurvivesCrash() {
        MongoSecretBoxCounterStore counters = new MongoSecretBoxCounterStore(template, CLOCK);
        byte[] key = SecretBox.generateKey();
        byte[] aad = SecretBox.aad("t", "u");
        PersistentSecretBox first = new PersistentSecretBox(counters, 100, "k1", key);
        for (int i = 0; i < 250; i++) {
            first.seal(Bytes.random(8), aad);
        }
        long used = first.messagesSealed("k1");
        PersistentSecretBox second = new PersistentSecretBox(counters, 100, "k1", key);
        assertTrue(second.messagesSealed("k1") >= used);
        byte[] box = first.seal(Bytes.random(8), aad);
        assertEquals(8, second.open(box, aad).length);
    }

    @Test
    @DisplayName("SSO-T-auth-store-023 F-T2 persisted ceiling is always ahead of the in-memory counter")
    void ceilingAhead() {
        MongoSecretBoxCounterStore counters = new MongoSecretBoxCounterStore(template, CLOCK);
        byte[] key = SecretBox.generateKey();
        byte[] aad = SecretBox.aad("t", "u");
        PersistentSecretBox box = new PersistentSecretBox(counters, 10, "k2", key);
        for (int i = 0; i < 95; i++) {
            box.seal(Bytes.random(8), aad);
            long allocated = template.findById("k2", Document.class, MongoSecretBoxCounterStore.COLLECTION).get("allocated", Number.class).longValue();
            assertTrue(allocated >= box.messagesSealed("k2"));
        }
        box.close();
        Document d = template.findById("k2", Document.class, MongoSecretBoxCounterStore.COLLECTION);
        assertEquals(95L, d.get("used", Number.class).longValue());
        assertEquals(100L, d.get("allocated", Number.class).longValue());
    }

    @Test
    @DisplayName("SSO-T-auth-store-024 F-T2 two instances sharing a kid reserve disjoint blocks")
    void sharedKid() {
        MongoSecretBoxCounterStore counters = new MongoSecretBoxCounterStore(template, CLOCK);
        byte[] key = SecretBox.generateKey();
        PersistentSecretBox a = new PersistentSecretBox(counters, 50, "k3", key);
        PersistentSecretBox b = new PersistentSecretBox(counters, 50, "k3", key);
        assertTrue(b.messagesSealed("k3") >= 50);
        assertEquals(0, a.messagesSealed("k3"));
    }

    @Test
    @DisplayName("SSO-T-auth-store-025 F-L1 recovery store persists the lookup id and codes redeem through Mongo")
    void recoveryLookup() {
        MongoRecoveryStore store = new MongoRecoveryStore(template);
        RecoveryCodes rc = new RecoveryCodes(CLOCK, store, Bytes.random(32), 1000, 5, Duration.ofMinutes(15));
        List<String> codes = rc.generate("lu");
        RecoverySet set = store.find("lu").orElseThrow();
        assertEquals(16, set.entries().get(0).lookupId().length);
        assertEquals(RecoveryCodes.Outcome.OK, rc.redeem("lu", codes.get(4)));
        assertEquals(RecoveryCodes.Outcome.INVALID, rc.redeem("lu", codes.get(4)));
        assertEquals(9, rc.remaining("lu"));
    }

    @Test
    @DisplayName("SSO-T-auth-store-026 session revoked concurrently with touch stays revoked and replace never upserts")
    void sessionRevokeWinsOverTouch() throws Exception {
        MongoSessionStore store = new MongoSessionStore(template);
        com.naqqa.sso.session.SessionService service = new com.naqqa.sso.session.SessionService(CLOCK, store,
                com.naqqa.sso.session.SessionConfig.defaults());
        for (int round = 0; round < 10; round++) {
            com.naqqa.sso.session.SessionService.Created c = service.create(new com.naqqa.sso.session.SessionService.CreateRequest(
                    "race" + round, "t", "app", "dev", "127.0.0.1", "ua", java.util.Set.of("pwd"), null, null));
            String token = c.token();
            race(8, () -> {
                if (Thread.currentThread().getId() % 2 == 0) {
                    service.revoke(token);
                } else {
                    try {
                        service.touch(token, "other");
                    } catch (RuntimeException ignored) {
                    }
                }
                return null;
            });
            service.revoke(token);
            race(8, () -> {
                try {
                    service.touch(token, "late");
                } catch (RuntimeException ignored) {
                }
                return null;
            });
            assertTrue(store.find(c.session().idHash()).isEmpty());
        }
        com.naqqa.sso.session.Session ghost = new com.naqqa.sso.session.Session("ghost", "u", "t", java.util.Set.of("a"), Instant.now(),
                Instant.now(), null, null, null, java.util.Set.of(), null, Instant.now());
        assertFalse(store.replace(ghost));
        assertTrue(store.find("ghost").isEmpty());
    }
}
