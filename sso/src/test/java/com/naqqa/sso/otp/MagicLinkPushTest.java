package com.naqqa.sso.otp;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.p2support.RecordingTransport;
import com.naqqa.sso.p2support.TestClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MagicLinkPushTest {
    private final TestClock clock = TestClock.at("2026-03-01T10:00:00Z");
    private final InMemoryMagicLinkStore store = new InMemoryMagicLinkStore();
    private final MagicLink ml = new MagicLink(clock, store, Set.of("https://app.naqqa.io/home", "https://app.naqqa.io/billing"));

    @Test
    @DisplayName("SSO-T-otp-021 magic link token is 32 bytes URL-safe")
    void tokenShape() {
        MagicLink.Issued i = ml.issue("u1", "https://app.naqqa.io/home", null);
        assertEquals(43, i.token().length());
        assertTrue(i.token().matches("[A-Za-z0-9_-]{43}"));
    }

    @Test
    @DisplayName("SSO-T-otp-022 magic link is stored as SHA-256 only")
    void hashedAtRest() {
        MagicLink.Issued i = ml.issue("u1", "https://app.naqqa.io/home", null);
        assertTrue(store.find(i.token()).isEmpty());
        assertDoesNotThrow(() -> ml.consume(i.token(), null));
    }

    @Test
    @DisplayName("SSO-T-otp-023 magic link is single use")
    void singleUse() {
        MagicLink.Issued i = ml.issue("u1", "https://app.naqqa.io/home", null);
        MagicLink.Consumed c = ml.consume(i.token(), null);
        assertEquals("u1", c.userId());
        assertEquals("https://app.naqqa.io/home", c.redirect());
        assertEquals("magic_invalid", assertThrows(SsoException.class, () -> ml.consume(i.token(), null)).code());
    }

    @Test
    @DisplayName("SSO-T-otp-024 magic link expires after 15 minutes")
    void expiry() {
        MagicLink.Issued i = ml.issue("u1", "https://app.naqqa.io/home", null);
        clock.advance(Duration.ofMinutes(15).minusSeconds(1));
        MagicLink.Issued j = ml.issue("u2", "https://app.naqqa.io/home", null);
        assertDoesNotThrow(() -> ml.consume(i.token(), null));
        clock.advance(Duration.ofMinutes(15));
        assertEquals("magic_invalid", assertThrows(SsoException.class, () -> ml.consume(j.token(), null)).code());
    }

    @Test
    @DisplayName("SSO-T-otp-025 magic link bound to browser cookie rejects another browser without burning the link")
    void browserBinding() {
        MagicLink.Issued i = ml.issue("u1", "https://app.naqqa.io/home", "cookie-A");
        assertThrows(SsoException.class, () -> ml.consume(i.token(), "cookie-B"));
        assertThrows(SsoException.class, () -> ml.consume(i.token(), null));
        assertEquals("u1", ml.consume(i.token(), "cookie-A").userId());
    }

    @Test
    @DisplayName("SSO-T-otp-026 magic link redirect must be in the allowlist")
    void redirectAllowlist() {
        assertEquals("magic_redirect_not_allowed", assertThrows(SsoException.class, () -> ml.issue("u", "https://evil.example/", null)).code());
        assertThrows(SsoException.class, () -> ml.issue("u", "https://app.naqqa.io/home/../x", null));
        assertThrows(SsoException.class, () -> ml.issue("u", "https://app.naqqa.io.evil.io/home", null));
        assertThrows(SsoException.class, () -> ml.issue("u", null, null));
    }

    @Test
    @DisplayName("SSO-T-otp-027 magic link failures are uniform for unknown, malformed and null tokens")
    void uniformFailure() {
        String a = assertThrows(SsoException.class, () -> ml.consume("x".repeat(43), null)).code();
        String b = assertThrows(SsoException.class, () -> ml.consume("short", null)).code();
        String c = assertThrows(SsoException.class, () -> ml.consume(null, null)).code();
        assertEquals(a, b);
        assertEquals(b, c);
    }

    @Test
    @DisplayName("SSO-T-otp-028 magic link tokens are unique")
    void unique() {
        assertNotEquals(ml.issue("u", "https://app.naqqa.io/home", null).token(), ml.issue("u", "https://app.naqqa.io/home", null).token());
    }

    private final RecordingTransport transport = new RecordingTransport();
    private final PushApproval push = new PushApproval(clock, transport);

    @Test
    @DisplayName("SSO-T-otp-029 push challenge has a 2 digit number and the device gets 3 choices containing it")
    void pushNumberMatching() {
        PushApproval.Challenge c = push.create("u1", "device-1");
        assertTrue(c.displayNumber().matches("[1-9][0-9]"));
        assertEquals(3, c.choices().size());
        assertTrue(c.choices().contains(c.displayNumber()));
        assertEquals(Channel.PUSH, transport.last().channel());
        assertEquals(PushApproval.Status.PENDING, push.status(c.id()));
    }

    @Test
    @DisplayName("SSO-T-otp-030 push approve with the right number then consume once")
    void pushApprove() {
        PushApproval.Challenge c = push.create("u1", "device-1");
        assertEquals(PushApproval.Status.APPROVED, push.respond(c.id(), "u1", c.displayNumber(), true));
        assertTrue(push.consume(c.id(), "u1"));
        assertFalse(push.consume(c.id(), "u1"));
    }

    @Test
    @DisplayName("SSO-T-otp-031 push deny")
    void pushDeny() {
        PushApproval.Challenge c = push.create("u1", "device-1");
        assertEquals(PushApproval.Status.DENIED, push.respond(c.id(), "u1", c.displayNumber(), false));
        assertFalse(push.consume(c.id(), "u1"));
    }

    @Test
    @DisplayName("SSO-T-otp-032 push wrong number twice denies the challenge")
    void pushWrongNumber() {
        PushApproval.Challenge c = push.create("u1", "device-1");
        String wrong = c.choices().stream().filter(n -> !n.equals(c.displayNumber())).findFirst().orElseThrow();
        assertEquals(PushApproval.Status.PENDING, push.respond(c.id(), "u1", wrong, true));
        assertEquals(PushApproval.Status.DENIED, push.respond(c.id(), "u1", wrong, true));
        assertEquals(PushApproval.Status.DENIED, push.respond(c.id(), "u1", c.displayNumber(), true));
    }

    @Test
    @DisplayName("SSO-T-otp-033 push expires after 2 minutes")
    void pushExpiry() {
        PushApproval.Challenge c = push.create("u1", "device-1");
        clock.advance(Duration.ofMinutes(2));
        assertEquals(PushApproval.Status.EXPIRED, push.status(c.id()));
        assertEquals(PushApproval.Status.EXPIRED, push.respond(c.id(), "u1", c.displayNumber(), true));
        assertFalse(push.consume(c.id(), "u1"));
    }

    @Test
    @DisplayName("SSO-T-otp-034 push cannot be answered or consumed by another user")
    void pushWrongUser() {
        PushApproval.Challenge c = push.create("u1", "device-1");
        assertEquals("push_invalid", assertThrows(SsoException.class, () -> push.respond(c.id(), "u2", c.displayNumber(), true)).code());
        push.respond(c.id(), "u1", c.displayNumber(), true);
        assertFalse(push.consume(c.id(), "u2"));
        assertEquals("push_invalid", assertThrows(SsoException.class, () -> push.status("unknown")).code());
    }

    @Test
    @DisplayName("SSO-T-otp-035 push approved just before expiry still consumable until expiry")
    void pushEdge() {
        PushApproval.Challenge c = push.create("u1", "device-1");
        clock.advance(Duration.ofSeconds(119));
        assertEquals(PushApproval.Status.APPROVED, push.respond(c.id(), "u1", c.displayNumber(), true));
        assertTrue(push.consume(c.id(), "u1"));
    }
}
