package com.naqqa.auth.sso.store;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.audit.AuditEntry;
import com.naqqa.sso.audit.AuditEvent;
import com.naqqa.sso.mfa.OtpAlgorithm;
import com.naqqa.sso.mfa.RecoverySet;
import com.naqqa.sso.mfa.TotpConfig;
import com.naqqa.sso.mfa.TotpCredential;
import com.naqqa.sso.otp.MagicLinkRecord;
import com.naqqa.sso.otp.OtpRecord;
import com.naqqa.sso.ratelimit.BucketState;
import com.naqqa.sso.ratelimit.FailureState;
import com.naqqa.sso.session.RefreshRecord;
import com.naqqa.sso.session.Session;
import com.naqqa.sso.tokens.ApiTokenEnv;
import com.naqqa.sso.tokens.ApiTokenKind;
import com.naqqa.sso.tokens.ApiTokenRecord;
import com.naqqa.sso.tokens.Cidr;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.webauthn.AttestationType;
import com.naqqa.sso.webauthn.Ceremony;
import com.naqqa.sso.webauthn.CoseAlgorithm;
import com.naqqa.sso.webauthn.CredentialRecord;
import com.naqqa.sso.webauthn.WebAuthnChallenge;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MongoStoresTest extends MongoStoreTestBase {

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    private static CredentialRecord credential(byte[] id, String userId) {
        return new CredentialRecord(id, Bytes.random(16), userId, Bytes.random(77), CoseAlgorithm.ES256, 5, true, true, false,
                List.of("internal"), Bytes.random(16), "none", AttestationType.NONE, false, now(), null, false);
    }

    @Test
    @DisplayName("SSO-T-auth-store-001 challenge consumed exactly once under 20 threads")
    void challengeSingleUse() throws Exception {
        MongoChallengeStore store = new MongoChallengeStore(template, CLOCK);
        byte[] value = Bytes.random(32);
        store.save(new WebAuthnChallenge(value, Ceremony.AUTHENTICATION, null, "u1", "n", "d", "b", now().plusSeconds(60)));
        List<Boolean> results = race(20, () -> store.consume(value).isPresent());
        assertEquals(1, results.stream().filter(b -> b).count());
        assertTrue(store.consume(value).isEmpty());
    }

    @Test
    @DisplayName("SSO-T-auth-store-002 expired challenge is not returned and is removed")
    void challengeExpired() {
        MongoChallengeStore store = new MongoChallengeStore(template, CLOCK);
        byte[] value = Bytes.random(32);
        store.save(new WebAuthnChallenge(value, Ceremony.REGISTRATION, Bytes.random(16), "u1", "n", "d", null, now().minusSeconds(1)));
        assertTrue(store.consume(value).isEmpty());
        assertTrue(store.consume(value).isEmpty());
    }

    @Test
    @DisplayName("SSO-T-auth-store-003 challenge round trip keeps every field")
    void challengeRoundTrip() {
        MongoChallengeStore store = new MongoChallengeStore(template, CLOCK);
        byte[] value = Bytes.random(32);
        byte[] handle = Bytes.random(16);
        Instant exp = now().plusSeconds(60);
        store.save(new WebAuthnChallenge(value, Ceremony.REGISTRATION, handle, "u1", "name", "display", "bind", exp));
        WebAuthnChallenge c = store.consume(value).orElseThrow();
        assertArrayEquals(handle, c.userHandle());
        assertEquals("u1", c.userId());
        assertEquals("bind", c.binding());
        assertEquals(exp, c.expiresAt());
        assertEquals(Ceremony.REGISTRATION, c.ceremony());
    }

    @Test
    @DisplayName("SSO-T-auth-store-004 credential create is unique and lookups work")
    void credentialCreateFind() {
        MongoCredentialStore store = new MongoCredentialStore(template);
        byte[] id = Bytes.random(32);
        CredentialRecord r = credential(id, "user-a");
        store.create(r);
        SsoException ex = assertThrows(SsoException.class, () -> store.create(credential(id, "user-b")));
        assertEquals("webauthn.credential_exists", ex.code());
        CredentialRecord found = store.findById(id).orElseThrow();
        assertArrayEquals(r.publicKeyCose(), found.publicKeyCose());
        assertEquals(1, store.findByUserHandle(r.userHandle()).size());
        assertEquals(1, store.findByUserId("user-a").size());
        assertTrue(store.findById(Bytes.random(32)).isEmpty());
        assertTrue(store.delete(id));
        assertFalse(store.delete(id));
    }

    @Test
    @DisplayName("SSO-T-auth-store-005 credential update is compare and swap, 20 threads one winner")
    void credentialUpdateCas() throws Exception {
        MongoCredentialStore store = new MongoCredentialStore(template);
        byte[] id = Bytes.random(32);
        CredentialRecord r = credential(id, "user-c");
        store.create(r);
        AtomicInteger n = new AtomicInteger();
        List<Boolean> results = race(20, () -> {
            long count = 100 + n.incrementAndGet();
            return store.update(r, r.withUsage(count, false, true, now()));
        });
        assertEquals(1, results.stream().filter(b -> b).count());
        assertTrue(store.findById(id).orElseThrow().signCount() > 100);
    }

    @Test
    @DisplayName("SSO-T-auth-store-006 totp step advances monotonically, one winner per step")
    void totpStep() throws Exception {
        MongoTotpStepStore store = new MongoTotpStepStore(template, CLOCK);
        assertEquals(-1L, store.lastStep("tu"));
        List<Boolean> results = race(20, () -> store.advance("tu", 1000));
        assertEquals(1, results.stream().filter(b -> b).count());
        assertEquals(1000L, store.lastStep("tu"));
        assertFalse(store.advance("tu", 1000));
        assertFalse(store.advance("tu", 999));
        assertTrue(store.advance("tu", 1001));
        store.clear("tu");
        assertEquals(-1L, store.lastStep("tu"));
    }

    @Test
    @DisplayName("SSO-T-auth-store-007 totp credential stores sealed bytes only and round trips")
    void totpCredential() {
        MongoTotpCredentialStore store = new MongoTotpCredentialStore(template);
        byte[] sealed = Bytes.random(48);
        store.save(new TotpCredential("tc", sealed, new TotpConfig(OtpAlgorithm.SHA256, 8, 60, 2), false, now(), null));
        TotpCredential c = store.find("tc").orElseThrow();
        assertArrayEquals(sealed, c.sealedSecret());
        assertEquals(OtpAlgorithm.SHA256, c.config().algorithm());
        assertEquals(8, c.config().digits());
        assertFalse(c.confirmed());
        Document raw = template.findById("tc", Document.class, MongoTotpCredentialStore.COLLECTION);
        assertFalse(raw.containsKey("secret"));
        store.delete("tc");
        assertTrue(store.find("tc").isEmpty());
    }

    @Test
    @DisplayName("SSO-T-auth-store-008 recovery set round trips and replaces")
    void recovery() {
        MongoRecoveryStore store = new MongoRecoveryStore(template);
        Instant failure = now();
        store.save(new RecoverySet("ru", List.of(new RecoverySet.Entry(Bytes.random(16), Bytes.random(32), false),
                new RecoverySet.Entry(Bytes.random(16), Bytes.random(32), true)), 2, failure));
        RecoverySet s = store.find("ru").orElseThrow();
        assertEquals(2, s.entries().size());
        assertTrue(s.entries().get(1).used());
        assertEquals(2, s.failedAttempts());
        assertEquals(failure, s.lastFailure());
        store.save(new RecoverySet("ru", List.of(), 0, null));
        assertEquals(0, store.find("ru").orElseThrow().entries().size());
        store.delete("ru");
        assertTrue(store.find("ru").isEmpty());
    }

    @Test
    @DisplayName("SSO-T-auth-store-009 otp record, sends and destination invalidation")
    void otp() {
        MongoOtpStore store = new MongoOtpStore(template);
        Instant t = now();
        store.save(new OtpRecord("o1", "dest", "login", "mac", t, t.plusSeconds(300), 0, false));
        store.save(new OtpRecord("o2", "dest", "login", "mac2", t, t.plusSeconds(300), 1, false));
        assertEquals(1, store.find("o2").orElseThrow().attempts());
        store.recordSend("dest", t.minusSeconds(100));
        store.recordSend("dest", t);
        assertEquals(t, store.lastSend("dest").orElseThrow());
        assertEquals(1, store.sendsSince("dest", t.minusSeconds(10)));
        assertEquals(2, store.sendsSince("dest", t.minusSeconds(1000)));
        assertTrue(store.lastSend("none").isEmpty());
        store.invalidateDestination("dest");
        assertTrue(store.find("o1").orElseThrow().consumed());
        assertTrue(store.find("o2").orElseThrow().consumed());
    }

    @Test
    @DisplayName("SSO-T-auth-store-017 otp attempts: atomic increment-and-check caps 20 parallel guesses at maxAttempts, consume flips once")
    void otpAtomicAttempts() throws Exception {
        MongoOtpStore store = new MongoOtpStore(template);
        Instant t = now();
        store.save(new OtpRecord("oa", "dest-a", "login", "mac", t, t.plusSeconds(300), 0, false));
        List<Boolean> reserved = race(20, () -> store.recordAttempt("oa", 5).isPresent());
        assertEquals(5, reserved.stream().filter(b -> b).count());
        assertEquals(5, store.find("oa").orElseThrow().attempts());
        assertTrue(store.recordAttempt("oa", 5).isEmpty());
        store.save(new OtpRecord("ob", "dest-b", "login", "mac", t, t.plusSeconds(300), 0, false));
        List<Boolean> consumed = race(20, () -> store.consume("ob"));
        assertEquals(1, consumed.stream().filter(b -> b).count());
        assertTrue(store.recordAttempt("ob", 5).isEmpty());
        assertTrue(store.recordAttempt("missing", 5).isEmpty());
    }

    @Test
    @DisplayName("SSO-T-auth-store-010 magic link markUsed single use under 20 threads")
    void magicLink() throws Exception {
        MongoMagicLinkStore store = new MongoMagicLinkStore(template, CLOCK);
        store.save(new MagicLinkRecord("h1", "u", "/home", "bind", now().plusSeconds(300), false));
        List<Boolean> results = race(20, () -> store.markUsed("h1"));
        assertEquals(1, results.stream().filter(b -> b).count());
        assertTrue(store.find("h1").orElseThrow().used());
        store.save(new MagicLinkRecord("h2", "u", "/home", null, now().minusSeconds(1), false));
        assertFalse(store.markUsed("h2"));
        assertFalse(store.markUsed("missing"));
    }

    @Test
    @DisplayName("SSO-T-auth-store-011 session save, find, list and delete by user")
    void sessions() {
        MongoSessionStore store = new MongoSessionStore(template);
        Instant t = now();
        Session s1 = new Session("s1", "su", "tenant", Set.of("app1", "app2"), t, t, "dev", "1.2.3.4", "ua", Set.of("pwd", "otp"), "2", t);
        store.save(s1);
        store.save(new Session("s2", "su", "tenant", Set.of("app1"), t, t, "dev2", "1.2.3.5", "ua", Set.of("pwd"), "1", t));
        Session found = store.find("s1").orElseThrow();
        assertEquals(Set.of("app1", "app2"), found.clientIds());
        assertEquals(Set.of("pwd", "otp"), found.amr());
        assertEquals("tenant", found.tenantId());
        assertEquals(2, store.listByUser("su").size());
        store.delete("s1");
        assertTrue(store.find("s1").isEmpty());
        assertEquals(1, store.deleteByUser("su"));
        assertTrue(store.listByUser("su").isEmpty());
    }

    @Test
    @DisplayName("SSO-T-auth-store-012 refresh markRotated single winner and family revocation")
    void refresh() throws Exception {
        MongoRefreshStore store = new MongoRefreshStore(template, CLOCK, Duration.ofDays(30));
        Instant t = now();
        store.save(new RefreshRecord("r1", "fam1", "ru1", "app", t, t.plus(Duration.ofDays(1)), false));
        store.save(new RefreshRecord("r2", "fam2", "ru1", "app", t, t.plus(Duration.ofDays(1)), false));
        List<Boolean> results = race(20, () -> store.markRotated("r1"));
        assertEquals(1, results.stream().filter(b -> b).count());
        assertTrue(store.find("r1").orElseThrow().rotated());
        assertFalse(store.isFamilyRevoked("fam1"));
        store.revokeFamily("fam1");
        assertTrue(store.isFamilyRevoked("fam1"));
        assertFalse(store.isFamilyRevoked("fam2"));
        store.revokeAllForUser("ru1");
        assertTrue(store.isFamilyRevoked("fam2"));
        assertFalse(store.markRotated("missing"));
    }

    @Test
    @DisplayName("SSO-T-auth-store-013 rate limit state round trips and atomically serializes 20 threads")
    void rateLimit() throws Exception {
        MongoRateLimitStore store = new MongoRateLimitStore(template, CLOCK, Duration.ofDays(1));
        assertNull(store.bucket("k"));
        store.putBucket("k", new BucketState(3.5, 123L));
        assertEquals(new BucketState(3.5, 123L), store.bucket("k"));
        store.putWindow("k", List.of(1L, 2L, 3L));
        assertEquals(List.of(1L, 2L, 3L), store.window("k"));
        store.putFailures("k", new FailureState(4, 99L));
        assertEquals(new FailureState(4, 99L), store.failures("k"));
        assertEquals(new BucketState(3.5, 123L), store.bucket("k"));
        store.remove("k");
        assertNull(store.bucket("k"));
        assertNull(store.failures("k"));
        assertTrue(store.window("k").isEmpty());

        MongoRateLimitStore second = new MongoRateLimitStore(template, CLOCK, Duration.ofDays(1));
        race(20, () -> {
            MongoRateLimitStore s = Thread.currentThread().getId() % 2 == 0 ? store : second;
            s.atomically(() -> {
                FailureState f = s.failures("counter");
                int next = f == null ? 1 : f.count() + 1;
                s.putFailures("counter", new FailureState(next, 0));
                return null;
            });
            return null;
        });
        assertEquals(20, store.failures("counter").count());
    }

    @Test
    @DisplayName("SSO-T-auth-store-014 audit append keeps order, rejects duplicate seq and dotted attribute keys")
    void audit() {
        MongoAuditStore store = new MongoAuditStore(template);
        AuditEvent ev = new AuditEvent(now(), "tenant", "actor", "login", "target", "ok", "1.1.1.1", Map.of("a.b", "x", "c", "y"));
        store.append(new AuditEntry(1, ev, "0", "h1", "k1", "sig1"));
        store.append(new AuditEntry(2, ev, "h1", "h2", "k1", "sig2"));
        assertThrows(RuntimeException.class, () -> store.append(new AuditEntry(2, ev, "h1", "hx", "k1", "sigx")));
        List<AuditEntry> all = store.all();
        assertEquals(2, all.size());
        assertEquals(1, all.get(0).seq());
        assertEquals("h2", all.get(1).hash());
        assertEquals(ev.attributes(), all.get(0).event().attributes());
        assertEquals(ev.time(), all.get(0).event().time());
    }

    @Test
    @DisplayName("SSO-T-auth-store-015 api token round trip, tenant lookup, unique hash, delete")
    void apiTokens() {
        MongoApiTokenStore store = new MongoApiTokenStore(template);
        Instant t = now();
        ApiTokenRecord r = new ApiTokenRecord("id1", "tenant-x", ApiTokenKind.SECRET, ApiTokenEnv.LIVE, "n", Set.of("a", "b"),
                List.of(Cidr.parse("10.0.0.0/8")), "hash-1", null, null, t, null, null, 0, null);
        store.save(r);
        ApiTokenRecord found = store.findById("id1").orElseThrow();
        assertEquals(Set.of("a", "b"), found.scopes());
        assertEquals(1, found.allowlist().size());
        assertTrue(found.allowlist().get(0).contains("10.1.2.3"));
        assertEquals(1, store.findByTenant("tenant-x").size());
        assertTrue(store.findByTenant("other").isEmpty());
        store.save(found.withFailures(3, t.plusSeconds(60)));
        assertEquals(3, store.findById("id1").orElseThrow().failedAttempts());
        ApiTokenRecord dup = new ApiTokenRecord("id2", "tenant-x", ApiTokenKind.PUBLISHABLE, ApiTokenEnv.TEST, "n", Set.of(),
                List.of(), "hash-1", null, null, t, null, null, 0, null);
        assertThrows(RuntimeException.class, () -> store.save(dup));
        assertTrue(store.delete("id1"));
        assertFalse(store.delete("id1"));
    }

    @Test
    @DisplayName("SSO-T-auth-store-016 indexes: ttl on expiring collections, unique hash, tenant indexes")
    void indexes() {
        for (String c : List.of(MongoChallengeStore.COLLECTION, MongoTotpStepStore.COLLECTION, MongoOtpStore.COLLECTION,
                MongoOtpStore.SENDS, MongoMagicLinkStore.COLLECTION, MongoRefreshStore.COLLECTION, MongoRefreshStore.REVOKED,
                MongoRateLimitStore.COLLECTION)) {
            boolean ttl = template.indexOps(c).getIndexInfo().stream().anyMatch(i -> i.getExpireAfter().isPresent());
            assertTrue(ttl, c);
        }
        assertTrue(template.indexOps(MongoApiTokenStore.COLLECTION).getIndexInfo().stream().anyMatch(i -> i.isUnique() && i.getName().equals("hash_unique")));
        assertTrue(template.indexOps(MongoSessionStore.COLLECTION).getIndexInfo().stream().anyMatch(i -> i.getName().equals("tenantId")));
        assertTrue(template.indexOps(MongoApiTokenStore.COLLECTION).getIndexInfo().stream().anyMatch(i -> i.getName().equals("tenantId")));
    }
}
