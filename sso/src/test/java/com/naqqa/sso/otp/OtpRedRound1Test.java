package com.naqqa.sso.otp;

import com.naqqa.sso.p2support.RecordingTransport;
import com.naqqa.sso.p2support.TestClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class OtpRedRound1Test {
    private final TestClock clock = TestClock.at("2026-03-01T10:00:00Z");
    private final PushApproval push = new PushApproval(clock, new RecordingTransport());

    @Test
    @DisplayName("SSO-T-otp-041 F-R1-4 an approved push challenge cannot be consumed after its TTL")
    void approvedPushExpires() {
        PushApproval.Challenge c = push.create("u1", "device-1");
        assertEquals(PushApproval.Status.APPROVED, push.respond(c.id(), "u1", c.displayNumber(), true));
        clock.advance(Duration.ofMinutes(3));
        assertFalse(push.consume(c.id(), "u1"));
        assertNotEquals(PushApproval.Status.APPROVED, push.status(c.id()));
        PushApproval.Challenge d = push.create("u1", "device-1");
        assertEquals(PushApproval.Status.APPROVED, push.respond(d.id(), "u1", d.displayNumber(), true));
        assertTrue(push.consume(d.id(), "u1"));
        assertFalse(push.consume(d.id(), "u1"));
    }
}
