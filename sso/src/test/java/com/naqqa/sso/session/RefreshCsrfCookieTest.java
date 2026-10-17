package com.naqqa.sso.session;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.p2support.TestClock;
import com.naqqa.sso.util.Bytes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class RefreshCsrfCookieTest {
    private final TestClock clock = TestClock.at("2026-04-01T08:00:00Z");
    private final InMemoryRefreshStore store = new InMemoryRefreshStore();
    private final RefreshTokenService svc = new RefreshTokenService(clock, store, Duration.ofDays(30));

    @Test
    @DisplayName("SSO-T-session-018 refresh token rotates on every use")
    void rotation() {
        RefreshTokenService.Issued i = svc.issue("u1", "app1");
        RefreshTokenService.Rotation r1 = svc.rotate(i.token(), "app1");
        assertEquals(RefreshTokenService.Status.OK, r1.status());
        assertNotEquals(i.token(), r1.token());
        assertEquals(i.familyId(), r1.familyId());
        RefreshTokenService.Rotation r2 = svc.rotate(r1.token(), "app1");
        assertEquals(RefreshTokenService.Status.OK, r2.status());
        assertEquals("u1", r2.userId());
    }

    @Test
    @DisplayName("SSO-T-session-019 reuse of a rotated token reports REUSE_DETECTED and revokes the family")
    void reuse() {
        RefreshTokenService.Issued i = svc.issue("u1", "app1");
        RefreshTokenService.Rotation r1 = svc.rotate(i.token(), "app1");
        assertEquals(RefreshTokenService.Status.REUSE_DETECTED, svc.rotate(i.token(), "app1").status());
        assertEquals(RefreshTokenService.Status.REVOKED, svc.rotate(r1.token(), "app1").status());
    }

    @Test
    @DisplayName("SSO-T-session-020 reuse does not affect other families of the same user")
    void reuseOtherFamily() {
        RefreshTokenService.Issued a = svc.issue("u1", "app1");
        RefreshTokenService.Issued b = svc.issue("u1", "app1");
        svc.rotate(a.token(), "app1");
        svc.rotate(a.token(), "app1");
        assertEquals(RefreshTokenService.Status.OK, svc.rotate(b.token(), "app1").status());
    }

    @Test
    @DisplayName("SSO-T-session-021 refresh token bound to its client, mismatch revokes the family")
    void clientBinding() {
        RefreshTokenService.Issued i = svc.issue("u1", "app1");
        assertEquals(RefreshTokenService.Status.CLIENT_MISMATCH, svc.rotate(i.token(), "app2").status());
        assertEquals(RefreshTokenService.Status.REVOKED, svc.rotate(i.token(), "app1").status());
    }

    @Test
    @DisplayName("SSO-T-session-022 family absolute lifetime is not extended by rotation")
    void familyLifetime() {
        RefreshTokenService.Issued i = svc.issue("u1", "app1");
        String token = i.token();
        for (int d = 0; d < 29; d++) {
            clock.advance(Duration.ofDays(1));
            RefreshTokenService.Rotation r = svc.rotate(token, "app1");
            assertEquals(RefreshTokenService.Status.OK, r.status());
            token = r.token();
        }
        clock.advance(Duration.ofDays(1).minusSeconds(1));
        RefreshTokenService.Rotation last = svc.rotate(token, "app1");
        assertEquals(RefreshTokenService.Status.OK, last.status());
        clock.advance(Duration.ofSeconds(1));
        assertEquals(RefreshTokenService.Status.EXPIRED, svc.rotate(last.token(), "app1").status());
    }

    @Test
    @DisplayName("SSO-T-session-023 unknown, null and malformed refresh tokens are INVALID")
    void invalid() {
        assertEquals(RefreshTokenService.Status.INVALID, svc.rotate("x".repeat(43), "app1").status());
        assertEquals(RefreshTokenService.Status.INVALID, svc.rotate(null, "app1").status());
        assertEquals(RefreshTokenService.Status.INVALID, svc.rotate("abc", "app1").status());
    }

    @Test
    @DisplayName("SSO-T-session-024 refresh tokens are stored hashed")
    void hashed() {
        RefreshTokenService.Issued i = svc.issue("u1", "app1");
        assertTrue(store.find(i.token()).isEmpty());
    }

    @Test
    @DisplayName("SSO-T-session-025 revokeAllForUser kills all families of that user")
    void revokeAll() {
        RefreshTokenService.Issued a = svc.issue("alice", "app1");
        RefreshTokenService.Issued b = svc.issue("alice", "app2");
        RefreshTokenService.Issued c = svc.issue("bob", "app1");
        svc.revokeAllForUser("alice");
        assertEquals(RefreshTokenService.Status.REVOKED, svc.rotate(a.token(), "app1").status());
        assertEquals(RefreshTokenService.Status.REVOKED, svc.rotate(b.token(), "app2").status());
        assertEquals(RefreshTokenService.Status.OK, svc.rotate(c.token(), "app1").status());
    }

    @Test
    @DisplayName("SSO-T-session-026 revoke by token kills its family")
    void revokeToken() {
        RefreshTokenService.Issued i = svc.issue("u1", "app1");
        svc.revoke(i.token());
        assertEquals(RefreshTokenService.Status.REVOKED, svc.rotate(i.token(), "app1").status());
    }

    @Test
    @DisplayName("SSO-T-session-027 concurrent rotation of the same token allows exactly one winner")
    void concurrent() throws Exception {
        RefreshTokenService.Issued i = svc.issue("u1", "app1");
        int n = 8;
        java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newFixedThreadPool(n);
        java.util.List<java.util.concurrent.Future<RefreshTokenService.Rotation>> fs = new java.util.ArrayList<>();
        for (int k = 0; k < n; k++) {
            fs.add(ex.submit(() -> svc.rotate(i.token(), "app1")));
        }
        int ok = 0;
        for (var f : fs) {
            if (f.get().status() == RefreshTokenService.Status.OK) {
                ok++;
            }
        }
        ex.shutdown();
        assertEquals(1, ok);
    }

    private final CsrfTokens csrf = new CsrfTokens(Bytes.random(32));

    @Test
    @DisplayName("SSO-T-session-028 csrf token verifies for its session")
    void csrfOk() {
        String t = csrf.issue("sess-1");
        assertTrue(csrf.verify("sess-1", t));
    }

    @Test
    @DisplayName("SSO-T-session-029 csrf token is bound to the session id")
    void csrfSessionBound() {
        String t = csrf.issue("sess-1");
        assertFalse(csrf.verify("sess-2", t));
    }

    @Test
    @DisplayName("SSO-T-session-030 csrf tampered, malformed and null tokens rejected")
    void csrfTamper() {
        String t = csrf.issue("sess-1");
        assertFalse(csrf.verify("sess-1", t.substring(0, t.length() - 2) + "AA"));
        assertFalse(csrf.verify("sess-1", "nodot"));
        assertFalse(csrf.verify("sess-1", "a.b.c"));
        assertFalse(csrf.verify("sess-1", null));
        assertFalse(csrf.verify(null, t));
    }

    @Test
    @DisplayName("SSO-T-session-031 csrf token from another key is rejected")
    void csrfKey() {
        String t = new CsrfTokens(Bytes.random(32)).issue("sess-1");
        assertFalse(csrf.verify("sess-1", t));
    }

    @Test
    @DisplayName("SSO-T-session-032 csrf double submit requires equal cookie and header and valid HMAC")
    void doubleSubmit() {
        String t = csrf.issue("sess-1");
        String u = csrf.issue("sess-1");
        assertTrue(csrf.verifyDoubleSubmit("sess-1", t, t));
        assertFalse(csrf.verifyDoubleSubmit("sess-1", t, u));
        assertFalse(csrf.verifyDoubleSubmit("sess-1", null, t));
        assertFalse(csrf.verifyDoubleSubmit("sess-1", "forged.value", "forged.value"));
    }

    @Test
    @DisplayName("SSO-T-session-033 csrf rejects short keys")
    void csrfShortKey() {
        assertThrows(SsoException.class, () -> new CsrfTokens(new byte[8]));
    }

    @Test
    @DisplayName("SSO-T-session-034 cookie defaults are Secure, HttpOnly, SameSite=Lax")
    void cookieDefaults() {
        String c = CookieBuilder.of("sid", "abc").build();
        assertEquals("sid=abc; Path=/; Secure; HttpOnly; SameSite=Lax", c);
    }

    @Test
    @DisplayName("SSO-T-session-035 __Host- prefix forces Secure, Path=/ and no Domain")
    void hostPrefix() {
        String c = CookieBuilder.of("sid", "abc").hostPrefix().maxAge(Duration.ofHours(1)).sameSite(CookieBuilder.SameSite.STRICT).build();
        assertEquals("__Host-sid=abc; Max-Age=3600; Path=/; Secure; HttpOnly; SameSite=Strict", c);
        assertThrows(SsoException.class, () -> CookieBuilder.of("sid", "a").hostPrefix().domain("x.io").build());
        assertThrows(SsoException.class, () -> CookieBuilder.of("sid", "a").hostPrefix().secure(false).build());
        assertThrows(SsoException.class, () -> CookieBuilder.of("sid", "a").hostPrefix().path("/app").build());
    }

    @Test
    @DisplayName("SSO-T-session-036 cookie SameSite=None requires Secure")
    void sameSiteNone() {
        assertThrows(SsoException.class, () -> CookieBuilder.of("a", "b").sameSite(CookieBuilder.SameSite.NONE).secure(false).build());
        assertTrue(CookieBuilder.of("a", "b").sameSite(CookieBuilder.SameSite.NONE).build().endsWith("SameSite=None"));
    }

    @Test
    @DisplayName("SSO-T-session-037 cookie rejects header injection in name, value, path and domain")
    void cookieInjection() {
        assertThrows(SsoException.class, () -> CookieBuilder.of("a", "b; Domain=evil"));
        assertThrows(SsoException.class, () -> CookieBuilder.of("a", "b\r\nSet-Cookie: x=y"));
        assertThrows(SsoException.class, () -> CookieBuilder.of("a b", "c"));
        assertThrows(SsoException.class, () -> CookieBuilder.of("a", "b").path("/x; Secure"));
        assertThrows(SsoException.class, () -> CookieBuilder.of("a", "b").domain("x.io; HttpOnly"));
    }

    @Test
    @DisplayName("SSO-T-session-038 expire cookie has Max-Age=0")
    void expire() {
        assertTrue(CookieBuilder.expire("sid", true).startsWith("__Host-sid=; Max-Age=0; Path=/"));
        assertTrue(CookieBuilder.expire("sid", false).contains("Max-Age=0"));
    }

    @Test
    @DisplayName("SSO-T-session-039 cookie domain attribute is emitted when set")
    void domain() {
        assertTrue(CookieBuilder.of("a", "b").domain("naqqa.io").build().contains("Domain=naqqa.io"));
    }
}
