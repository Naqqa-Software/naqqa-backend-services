package com.naqqa.sso.session;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.p2support.TestClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SessionRedRound1Test {
    private final TestClock clock = TestClock.at("2026-04-01T08:00:00Z");

    private static final class RacingStore implements SessionStore {
        final InMemorySessionStore inner = new InMemorySessionStore();
        volatile boolean revokeAfterNextFind;

        @Override
        public void save(Session session) {
            inner.save(session);
        }

        @Override
        public boolean replace(Session session) {
            return inner.replace(session);
        }

        @Override
        public Optional<Session> find(String idHash) {
            Optional<Session> s = inner.find(idHash);
            if (revokeAfterNextFind) {
                revokeAfterNextFind = false;
                inner.delete(idHash);
            }
            return s;
        }

        @Override
        public void delete(String idHash) {
            inner.delete(idHash);
        }

        @Override
        public List<Session> listByUser(String userId) {
            return inner.listByUser(userId);
        }

        @Override
        public int deleteByUser(String userId) {
            return inner.deleteByUser(userId);
        }
    }

    @Test
    @DisplayName("SSO-T-session-040 F-R1-2 touch and reauthenticate racing a revoke must not resurrect the revoked session")
    void touchDoesNotResurrect() {
        RacingStore store = new RacingStore();
        SessionService svc = new SessionService(clock, store, new SessionConfig(Duration.ofDays(30), Duration.ofHours(2)));
        SessionService.Created c = svc.create(new SessionService.CreateRequest("u1", "t1", "app1", null, "10.0.0.1", "ua",
                Set.of("pwd"), "urn:naqqa:acr:1", clock.instant()));
        store.revokeAfterNextFind = true;
        assertThrows(SsoException.class, () -> svc.touch(c.token(), "app2"));
        assertTrue(store.inner.find(c.session().idHash()).isEmpty());
        SessionService.Created d = svc.create(new SessionService.CreateRequest("u1", "t1", "app1", null, "10.0.0.1", "ua",
                Set.of("pwd"), "urn:naqqa:acr:1", clock.instant()));
        store.revokeAfterNextFind = true;
        assertThrows(SsoException.class, () -> svc.reauthenticate(d.token(), Set.of("pwd", "otp"), "urn:naqqa:acr:2"));
        assertTrue(store.inner.find(d.session().idHash()).isEmpty());
        SessionService.Created e = svc.create(new SessionService.CreateRequest("u1", "t1", "app1", null, "10.0.0.1", "ua",
                Set.of("pwd"), "urn:naqqa:acr:1", clock.instant()));
        assertTrue(svc.touch(e.token(), "app2").clientIds().contains("app2"));
    }

    @Test
    @DisplayName("SSO-T-session-041 F-O1 refresh token status lookup reflects revocation, rotation and expiry without rotating")
    void refreshStatus() {
        RefreshTokenService svc = new RefreshTokenService(clock, new InMemoryRefreshStore(), Duration.ofDays(1));
        RefreshTokenService.Issued a = svc.issue("u1", "c1");
        assertEquals(RefreshTokenService.Status.OK, svc.status(a.token()));
        assertEquals(RefreshTokenService.Status.OK, svc.status(a.token()));
        assertEquals(RefreshTokenService.Status.INVALID, svc.status("x".repeat(43)));
        assertEquals(RefreshTokenService.Status.INVALID, svc.status(null));
        RefreshTokenService.Rotation r = svc.rotate(a.token(), "c1");
        assertEquals(RefreshTokenService.Status.OK, r.status());
        assertEquals(RefreshTokenService.Status.REUSE_DETECTED, svc.status(a.token()));
        assertEquals(RefreshTokenService.Status.OK, svc.status(r.token()));
        svc.revokeAllForUser("u1");
        assertEquals(RefreshTokenService.Status.REVOKED, svc.status(r.token()));
        RefreshTokenService.Issued b = svc.issue("u2", "c1");
        clock.advance(Duration.ofDays(2));
        assertEquals(RefreshTokenService.Status.EXPIRED, svc.status(b.token()));
    }
}
