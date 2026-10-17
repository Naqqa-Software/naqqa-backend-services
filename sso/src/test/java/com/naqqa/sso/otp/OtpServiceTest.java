package com.naqqa.sso.otp;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.p2support.RecordingTransport;
import com.naqqa.sso.p2support.TestClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class OtpServiceTest {
    private final TestClock clock = TestClock.at("2026-03-01T10:00:00Z");
    private final RecordingTransport transport = new RecordingTransport();
    private final InMemoryOtpStore store = new InMemoryOtpStore();
    private final byte[] pepper = "0123456789abcdef0123456789abcdef".getBytes();
    private final OtpService svc = new OtpService(clock, pepper, store, transport, OtpConfig.defaults());

    @Test
    @DisplayName("SSO-T-otp-001 request sends a 6 digit code via the transport")
    void sendsCode() {
        svc.request(Channel.EMAIL, "a@x.io", "login", true);
        assertEquals(1, transport.sent.size());
        assertTrue(transport.last().vars().get("code").matches("\\d{6}"));
        assertEquals(Channel.EMAIL, transport.last().channel());
    }

    @Test
    @DisplayName("SSO-T-otp-002 valid code verifies once only")
    void singleUse() {
        OtpService.Requested r = svc.request(Channel.SMS, "+40700000000", "login", true);
        String code = transport.last().vars().get("code");
        assertEquals(OtpService.Outcome.OK, svc.verify(r.challengeId(), "login", code));
        assertEquals(OtpService.Outcome.INVALID, svc.verify(r.challengeId(), "login", code));
    }

    @Test
    @DisplayName("SSO-T-otp-003 code expires at ttl boundary")
    void expiry() {
        OtpService.Requested r = svc.request(Channel.EMAIL, "a@x.io", "login", true);
        String code = transport.last().vars().get("code");
        clock.advance(Duration.ofMinutes(10).minusSeconds(1));
        OtpService.Requested r2 = r;
        assertEquals(OtpService.Outcome.OK, svc.verify(r2.challengeId(), "login", code));
        clock.advance(Duration.ofHours(1));
        OtpService.Requested r3 = svc.request(Channel.EMAIL, "a@x.io", "login", true);
        String code3 = transport.last().vars().get("code");
        clock.advance(Duration.ofMinutes(10));
        assertEquals(OtpService.Outcome.EXPIRED, svc.verify(r3.challengeId(), "login", code3));
    }

    @Test
    @DisplayName("SSO-T-otp-004 five wrong attempts exhaust the challenge, even the right code fails afterwards")
    void bruteForce() {
        OtpService.Requested r = svc.request(Channel.EMAIL, "a@x.io", "login", true);
        String code = transport.last().vars().get("code");
        String wrong = code.equals("000000") ? "000001" : "000000";
        for (int i = 0; i < 4; i++) {
            assertEquals(OtpService.Outcome.INVALID, svc.verify(r.challengeId(), "login", wrong));
        }
        assertEquals(OtpService.Outcome.EXHAUSTED, svc.verify(r.challengeId(), "login", wrong));
        assertEquals(OtpService.Outcome.EXHAUSTED, svc.verify(r.challengeId(), "login", code));
    }

    @Test
    @DisplayName("SSO-T-otp-005 right code on the last allowed attempt succeeds")
    void lastAttempt() {
        OtpService.Requested r = svc.request(Channel.EMAIL, "a@x.io", "login", true);
        String code = transport.last().vars().get("code");
        String wrong = code.equals("000000") ? "000001" : "000000";
        for (int i = 0; i < 4; i++) {
            svc.verify(r.challengeId(), "login", wrong);
        }
        assertEquals(OtpService.Outcome.OK, svc.verify(r.challengeId(), "login", code));
    }

    @Test
    @DisplayName("SSO-T-otp-006 resend inside the cooldown is refused, after it is allowed")
    void cooldown() {
        svc.request(Channel.EMAIL, "a@x.io", "login", true);
        clock.advance(Duration.ofSeconds(59));
        assertEquals("otp_resend_cooldown", assertThrows(SsoException.class, () -> svc.request(Channel.EMAIL, "a@x.io", "login", true)).code());
        clock.advance(Duration.ofSeconds(1));
        assertDoesNotThrow(() -> svc.request(Channel.EMAIL, "a@x.io", "login", true));
    }

    @Test
    @DisplayName("SSO-T-otp-007 daily cap per destination")
    void dailyCap() {
        OtpService capped = new OtpService(clock, pepper, store, transport, new OtpConfig(6, Duration.ofMinutes(10), 5, Duration.ofSeconds(1), 3));
        for (int i = 0; i < 3; i++) {
            capped.request(Channel.SMS, "+40711111111", "login", true);
            clock.advance(Duration.ofSeconds(2));
        }
        assertEquals("otp_daily_cap", assertThrows(SsoException.class, () -> capped.request(Channel.SMS, "+40711111111", "login", true)).code());
        clock.advance(Duration.ofHours(24));
        assertDoesNotThrow(() -> capped.request(Channel.SMS, "+40711111111", "login", true));
    }

    @Test
    @DisplayName("SSO-T-otp-008 daily cap is per destination")
    void capPerDestination() {
        OtpService capped = new OtpService(clock, pepper, store, transport, new OtpConfig(6, Duration.ofMinutes(10), 5, Duration.ofSeconds(1), 1));
        capped.request(Channel.SMS, "+40711111111", "login", true);
        assertDoesNotThrow(() -> capped.request(Channel.SMS, "+40722222222", "login", true));
    }

    @Test
    @DisplayName("SSO-T-otp-009 unknown destination gets an identical response shape and no message is sent")
    void enumerationUniform() {
        OtpService.Requested known = svc.request(Channel.EMAIL, "real@x.io", "login", true);
        OtpService.Requested unknown = svc.request(Channel.EMAIL, "ghost@x.io", "login", false);
        assertEquals(1, transport.sent.size());
        assertEquals(known.challengeId().length(), unknown.challengeId().length());
        assertEquals(known.expiresAt(), unknown.expiresAt());
        assertEquals(known.resendAvailableAt(), unknown.resendAvailableAt());
    }

    @Test
    @DisplayName("SSO-T-otp-010 unknown destination is throttled exactly like a known one")
    void enumerationThrottle() {
        svc.request(Channel.EMAIL, "ghost@x.io", "login", false);
        assertEquals("otp_resend_cooldown", assertThrows(SsoException.class, () -> svc.request(Channel.EMAIL, "ghost@x.io", "login", false)).code());
    }

    @Test
    @DisplayName("SSO-T-otp-011 verify on a decoy challenge behaves like a wrong code and exhausts equally")
    void decoyVerify() {
        OtpService.Requested unknown = svc.request(Channel.EMAIL, "ghost@x.io", "login", false);
        for (int i = 0; i < 4; i++) {
            assertEquals(OtpService.Outcome.INVALID, svc.verify(unknown.challengeId(), "login", "123456"));
        }
        assertEquals(OtpService.Outcome.EXHAUSTED, svc.verify(unknown.challengeId(), "login", "123456"));
    }

    @Test
    @DisplayName("SSO-T-otp-012 code is bound to its purpose")
    void purposeBinding() {
        OtpService.Requested r = svc.request(Channel.EMAIL, "a@x.io", "login", true);
        String code = transport.last().vars().get("code");
        assertEquals(OtpService.Outcome.INVALID, svc.verify(r.challengeId(), "reset", code));
        assertEquals(OtpService.Outcome.OK, svc.verify(r.challengeId(), "login", code));
    }

    @Test
    @DisplayName("SSO-T-otp-013 a new request invalidates the previous challenge")
    void supersede() {
        OtpService.Requested first = svc.request(Channel.EMAIL, "a@x.io", "login", true);
        String code1 = transport.last().vars().get("code");
        clock.advance(Duration.ofSeconds(61));
        svc.request(Channel.EMAIL, "a@x.io", "login", true);
        assertEquals(OtpService.Outcome.INVALID, svc.verify(first.challengeId(), "login", code1));
    }

    @Test
    @DisplayName("SSO-T-otp-014 codes are stored as HMAC, not in clear")
    void hashedAtRest() {
        OtpService.Requested r = svc.request(Channel.EMAIL, "a@x.io", "login", true);
        String code = transport.last().vars().get("code");
        OtpRecord rec = store.find(r.challengeId()).orElseThrow();
        assertFalse(rec.codeMac().contains(code));
        assertEquals(43, rec.codeMac().length());
        assertFalse(rec.destinationKey().contains("a@x.io"));
    }

    @Test
    @DisplayName("SSO-T-otp-015 destination matching is case and whitespace insensitive")
    void normalizedDestination() {
        svc.request(Channel.EMAIL, "A@X.io", "login", true);
        assertThrows(SsoException.class, () -> svc.request(Channel.EMAIL, " a@x.io ", "login", true));
    }

    @Test
    @DisplayName("SSO-T-otp-016 verify handles nulls and unknown challenge ids")
    void nulls() {
        assertEquals(OtpService.Outcome.INVALID, svc.verify(null, "login", "123456"));
        assertEquals(OtpService.Outcome.INVALID, svc.verify("nope", "login", "123456"));
        assertEquals(OtpService.Outcome.INVALID, svc.verify("nope", null, "123456"));
    }

    @Test
    @DisplayName("SSO-T-otp-017 weak pepper and bad config rejected")
    void configChecks() {
        assertThrows(SsoException.class, () -> new OtpService(clock, new byte[4], store, transport, OtpConfig.defaults()));
        assertThrows(SsoException.class, () -> new OtpConfig(6, Duration.ZERO, 5, Duration.ZERO, 5));
        assertThrows(SsoException.class, () -> new OtpConfig(6, Duration.ofMinutes(1), 0, Duration.ZERO, 5));
    }

    @Test
    @DisplayName("SSO-T-otp-018 generated codes keep leading zeros and are uniformly 6 digits")
    void codeShape() {
        OtpService fast = new OtpService(clock, pepper, store, transport, new OtpConfig(6, Duration.ofMinutes(10), 5, Duration.ZERO, 100000));
        for (int i = 0; i < 300; i++) {
            fast.request(Channel.EMAIL, "u" + i + "@x.io", "login", true);
            assertEquals(6, transport.last().vars().get("code").length());
        }
    }

    @Test
    @DisplayName("SSO-T-otp-019 dev log transport masks destination and logs")
    void devLog() {
        assertEquals("a***@x***.io", DevLogTransport.mask("alice@xyz.io"));
        assertEquals("***00", DevLogTransport.mask("+40700000000"));
        assertEquals("***", DevLogTransport.mask(""));
        Logger logger = Logger.getLogger(DevLogTransport.class.getName());
        List<String> lines = new ArrayList<>();
        Handler h = new Handler() {
            @Override
            public void publish(LogRecord record) {
                lines.add(new java.util.logging.SimpleFormatter().formatMessage(record));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(h);
        try {
            new DevLogTransport().send(Channel.EMAIL, "alice@xyz.io", "otp.login", java.util.Map.of("code", "123456"));
        } finally {
            logger.removeHandler(h);
        }
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("a***@x***.io"));
        assertFalse(lines.get(0).contains("alice@xyz.io"));
    }

    @Test
    @DisplayName("SSO-T-otp-020 noop transport does nothing")
    void noop() {
        assertDoesNotThrow(() -> new NoopTransport().send(Channel.SMS, "x", "t", java.util.Map.of()));
    }
}
