package com.naqqa.sso.session;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.p2support.TestClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SessionServiceTest {
    private final TestClock clock = TestClock.at("2026-04-01T08:00:00Z");
    private final InMemorySessionStore store = new InMemorySessionStore();
    private final SessionService svc = new SessionService(clock, store, new SessionConfig(Duration.ofDays(30), Duration.ofHours(2)));

    private SessionService.Created create(String user, String client) {
        return svc.create(new SessionService.CreateRequest(user, "t1", client, "dev1", "10.0.0.1", "Mozilla/5.0", Set.of("pwd", "otp"), "urn:naqqa:acr:2", clock.instant()));
    }

    @Test
    @DisplayName("SSO-T-session-001 session id is 32 random bytes and only its SHA-256 is stored")
    void idHashed() {
        SessionService.Created c = create("u1", "app1");
        assertEquals(43, c.token().length());
        assertNotEquals(c.token(), c.session().idHash());
        assertEquals(SessionService.idHash(c.token()), c.session().idHash());
        assertTrue(store.find(c.token()).isEmpty());
        assertTrue(store.find(c.session().idHash()).isPresent());
    }

    @Test
    @DisplayName("SSO-T-session-002 session carries user, tenant, amr, acr, device, hashed user agent")
    void fields() {
        Session s = create("u1", "app1").session();
        assertEquals("u1", s.userId());
        assertEquals("t1", s.tenantId());
        assertEquals(Set.of("app1"), s.clientIds());
        assertEquals(Set.of("pwd", "otp"), s.amr());
        assertEquals("urn:naqqa:acr:2", s.acr());
        assertEquals("dev1", s.deviceId());
        assertNotEquals("Mozilla/5.0", s.userAgentHash());
        assertEquals(s.created(), s.authTime());
    }

    @Test
    @DisplayName("SSO-T-session-003 validate accepts a live session")
    void validate() {
        SessionService.Created c = create("u1", "app1");
        assertEquals("u1", svc.validate(c.token()).userId());
    }

    @Test
    @DisplayName("SSO-T-session-004 validate rejects unknown, null and malformed tokens uniformly")
    void invalid() {
        assertEquals("session_invalid", assertThrows(SsoException.class, () -> svc.validate("x".repeat(43))).code());
        assertEquals("session_invalid", assertThrows(SsoException.class, () -> svc.validate(null)).code());
        assertEquals("session_invalid", assertThrows(SsoException.class, () -> svc.validate("short")).code());
    }

    @Test
    @DisplayName("SSO-T-session-005 idle timeout edge: valid one second before, invalid at the boundary")
    void idleEdge() {
        SessionService.Created c = create("u1", "app1");
        clock.advance(Duration.ofHours(2).minusSeconds(1));
        assertDoesNotThrow(() -> svc.validate(c.token()));
        clock.advance(Duration.ofSeconds(1));
        assertEquals("session_idle_timeout", assertThrows(SsoException.class, () -> svc.validate(c.token())).code());
    }

    @Test
    @DisplayName("SSO-T-session-006 touch extends the idle window")
    void touchExtends() {
        SessionService.Created c = create("u1", "app1");
        clock.advance(Duration.ofHours(1));
        svc.touch(c.token(), "app1");
        clock.advance(Duration.ofHours(1).plusMinutes(30));
        assertDoesNotThrow(() -> svc.validate(c.token()));
    }

    @Test
    @DisplayName("SSO-T-session-007 absolute timeout cannot be extended by activity")
    void absoluteEdge() {
        SessionService svc = new SessionService(clock, store, new SessionConfig(Duration.ofDays(30), Duration.ofDays(2)));
        SessionService.Created c = svc.create(new SessionService.CreateRequest("u1", "t1", "app1", "dev1", "10.0.0.1", "UA", Set.of("pwd"), "urn:naqqa:acr:1", clock.instant()));
        for (int day = 0; day < 29; day++) {
            clock.advance(Duration.ofDays(1));
            svc.touch(c.token(), "app1");
        }
        clock.advance(Duration.ofDays(1).minusSeconds(1));
        assertDoesNotThrow(() -> svc.validate(c.token()));
        clock.advance(Duration.ofSeconds(1));
        assertEquals("session_expired", assertThrows(SsoException.class, () -> svc.touch(c.token(), "app1")).code());
    }

    @Test
    @DisplayName("SSO-T-session-008 expired sessions are removed from the store")
    void expiredRemoved() {
        SessionService.Created c = create("u1", "app1");
        clock.advance(Duration.ofHours(3));
        assertThrows(SsoException.class, () -> svc.validate(c.token()));
        assertTrue(store.find(c.session().idHash()).isEmpty());
    }

    @Test
    @DisplayName("SSO-T-session-009 touch records additional clients for SSO across apps")
    void ssoClients() {
        SessionService.Created c = create("u1", "app1");
        svc.touch(c.token(), "app2");
        Session s = svc.touch(c.token(), "app3");
        assertEquals(Set.of("app1", "app2", "app3"), s.clientIds());
    }

    @Test
    @DisplayName("SSO-T-session-010 revoke ends the session")
    void revoke() {
        SessionService.Created c = create("u1", "app1");
        svc.revoke(c.token());
        assertThrows(SsoException.class, () -> svc.validate(c.token()));
        assertDoesNotThrow(() -> svc.revoke(null));
    }

    @Test
    @DisplayName("SSO-T-session-011 revokeAllForUser ends every session of that user only")
    void revokeAll() {
        SessionService.Created a1 = create("alice", "app1");
        SessionService.Created a2 = create("alice", "app2");
        SessionService.Created b = create("bob", "app1");
        assertEquals(2, svc.revokeAllForUser("alice"));
        assertThrows(SsoException.class, () -> svc.validate(a1.token()));
        assertThrows(SsoException.class, () -> svc.validate(a2.token()));
        assertDoesNotThrow(() -> svc.validate(b.token()));
    }

    @Test
    @DisplayName("SSO-T-session-012 list returns only live sessions of the user")
    void list() {
        create("alice", "app1");
        create("alice", "app2");
        create("bob", "app1");
        assertEquals(2, svc.list("alice").size());
        clock.advance(Duration.ofHours(3));
        assertEquals(0, svc.list("alice").size());
    }

    @Test
    @DisplayName("SSO-T-session-013 revokeById only removes sessions of the stated user")
    void revokeById() {
        SessionService.Created a = create("alice", "app1");
        svc.revokeById("bob", a.session().idHash());
        assertDoesNotThrow(() -> svc.validate(a.token()));
        svc.revokeById("alice", a.session().idHash());
        assertThrows(SsoException.class, () -> svc.validate(a.token()));
    }

    @Test
    @DisplayName("SSO-T-session-014 device binding mismatch is rejected")
    void deviceBinding() {
        SessionService.Created c = create("u1", "app1");
        assertDoesNotThrow(() -> svc.validate(c.token(), "dev1"));
        assertEquals("session_device_mismatch", assertThrows(SsoException.class, () -> svc.validate(c.token(), "dev2")).code());
        assertThrows(SsoException.class, () -> svc.validate(c.token(), null));
    }

    @Test
    @DisplayName("SSO-T-session-015 reauthenticate updates amr, acr and authTime")
    void reauth() {
        SessionService.Created c = create("u1", "app1");
        clock.advance(Duration.ofMinutes(30));
        Session s = svc.reauthenticate(c.token(), Set.of("hwk", "user"), "urn:naqqa:acr:3");
        assertEquals(clock.instant(), s.authTime());
        assertEquals("urn:naqqa:acr:3", s.acr());
    }

    @Test
    @DisplayName("SSO-T-session-016 session tokens are unique")
    void unique() {
        assertNotEquals(create("u", "a").token(), create("u", "a").token());
    }

    @Test
    @DisplayName("SSO-T-session-017 invalid config rejected")
    void config() {
        assertThrows(SsoException.class, () -> new SessionConfig(Duration.ZERO, Duration.ofHours(1)));
        assertThrows(SsoException.class, () -> new SessionConfig(Duration.ofDays(1), Duration.ofSeconds(-1)));
        assertEquals(Duration.ofDays(30), SessionConfig.defaults().absoluteTimeout());
    }
}
