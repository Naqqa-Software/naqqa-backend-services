package com.naqqa.sso.mfa;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.p2support.TestClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PolicyStepUpTest {
    private final TestClock clock = TestClock.at("2026-01-01T12:00:00Z");
    private final MfaPolicy policy = new MfaPolicy(Set.of("admin", "billing"), Set.of(Factor.PASSWORD, Factor.TOTP, Factor.PASSKEY, Factor.SECURITY_KEY),
            Duration.ofMinutes(10), Duration.ofDays(30));
    private final StepUp stepUp = new StepUp(clock, policy);

    @Test
    @DisplayName("SSO-T-mfa-054 policy requires MFA for configured roles only")
    void requiredRoles() {
        assertTrue(policy.isRequiredFor(Set.of("user", "admin")));
        assertFalse(policy.isRequiredFor(Set.of("user")));
        assertFalse(policy.isRequiredFor(Set.of()));
    }

    @Test
    @DisplayName("SSO-T-mfa-055 policy allowed factor set")
    void allowedFactors() {
        assertTrue(policy.allows(Factor.TOTP));
        assertFalse(policy.allows(Factor.SMS_OTP));
    }

    @Test
    @DisplayName("SSO-T-mfa-056 policy rejects null and negative durations")
    void invalid() {
        assertThrows(SsoException.class, () -> new MfaPolicy(null, Set.of(), Duration.ZERO, Duration.ZERO));
        assertThrows(SsoException.class, () -> new MfaPolicy(Set.of(), Set.of(), Duration.ofSeconds(-1), Duration.ZERO));
    }

    @Test
    @DisplayName("SSO-T-mfa-057 acr level: password only is level 1")
    void acrPassword() {
        assertEquals(StepUp.ACR_PASSWORD, StepUp.acr(Set.of("pwd")));
    }

    @Test
    @DisplayName("SSO-T-mfa-058 acr level: password plus otp is MFA")
    void acrMfa() {
        assertEquals(StepUp.ACR_MFA, StepUp.acr(Set.of("pwd", "otp")));
        assertEquals(StepUp.ACR_MFA, StepUp.acr(Set.of("pwd", "sms")));
    }

    @Test
    @DisplayName("SSO-T-mfa-059 acr level: hardware or software key with user verification is phishing resistant")
    void acrPhishingResistant() {
        assertEquals(StepUp.ACR_PHISHING_RESISTANT, StepUp.acr(Set.of("hwk", "user")));
        assertEquals(StepUp.ACR_PHISHING_RESISTANT, StepUp.acr(Set.of("swk", "fpt", "user")));
        assertEquals(StepUp.ACR_PHISHING_RESISTANT, StepUp.acr(Set.of("swk", "face")));
    }

    @Test
    @DisplayName("SSO-T-mfa-060 acr: possession without user verification is not phishing resistant")
    void acrNoUv() {
        assertEquals(StepUp.ACR_MFA, StepUp.acr(Set.of("pwd", "hwk")));
    }

    @Test
    @DisplayName("SSO-T-mfa-061 factors map to RFC 8176 amr values")
    void amrMapping() {
        assertEquals(java.util.List.of("pwd"), Factor.PASSWORD.amr());
        assertEquals(java.util.List.of("otp"), Factor.TOTP.amr());
        assertEquals(java.util.List.of("sms"), Factor.SMS_OTP.amr());
        assertEquals(java.util.List.of("hwk", "user"), Factor.SECURITY_KEY.amr());
        assertTrue(Factor.PLATFORM_FACE.amr().contains("face"));
        assertTrue(Factor.PLATFORM_FINGERPRINT.amr().contains("fpt"));
        assertTrue(Factor.PASSKEY.amr().contains("swk"));
    }

    @Test
    @DisplayName("SSO-T-mfa-062 step-up satisfied for fresh MFA")
    void satisfied() {
        StepUp.Result r = stepUp.evaluate(new StepUp.AuthContext(clock.instant().minusSeconds(60), Set.of("pwd", "otp")), StepUp.ACR_MFA, true);
        assertTrue(r.satisfied());
        assertEquals(StepUp.Reason.NONE, r.reason());
    }

    @Test
    @DisplayName("SSO-T-mfa-063 step-up demanded when auth is older than max age")
    void tooOld() {
        StepUp.Result r = stepUp.evaluate(new StepUp.AuthContext(clock.instant().minus(Duration.ofMinutes(11)), Set.of("pwd", "otp")), StepUp.ACR_MFA, true);
        assertFalse(r.satisfied());
        assertEquals(StepUp.Reason.AUTH_TOO_OLD, r.reason());
    }

    @Test
    @DisplayName("SSO-T-mfa-064 step-up max age boundary is inclusive")
    void boundary() {
        Instant exactly = clock.instant().minus(Duration.ofMinutes(10));
        assertTrue(stepUp.evaluate(new StepUp.AuthContext(exactly, Set.of("pwd", "otp")), StepUp.ACR_MFA, true).satisfied());
        assertFalse(stepUp.evaluate(new StepUp.AuthContext(exactly.minusSeconds(1), Set.of("pwd", "otp")), StepUp.ACR_MFA, true).satisfied());
    }

    @Test
    @DisplayName("SSO-T-mfa-065 step-up demanded when acr is too low")
    void acrTooLow() {
        StepUp.Result r = stepUp.evaluate(new StepUp.AuthContext(clock.instant(), Set.of("pwd")), StepUp.ACR_MFA, true);
        assertEquals(StepUp.Reason.ACR_TOO_LOW, r.reason());
        StepUp.Result r3 = stepUp.evaluate(new StepUp.AuthContext(clock.instant(), Set.of("pwd", "otp")), StepUp.ACR_PHISHING_RESISTANT, true);
        assertEquals(StepUp.Reason.ACR_TOO_LOW, r3.reason());
    }

    @Test
    @DisplayName("SSO-T-mfa-066 step-up rejects a factor the policy does not allow")
    void notAllowed() {
        StepUp.Result r = stepUp.evaluate(new StepUp.AuthContext(clock.instant(), Set.of("pwd", "sms")), StepUp.ACR_MFA, true);
        assertEquals(StepUp.Reason.FACTOR_NOT_ALLOWED, r.reason());
    }

    @Test
    @DisplayName("SSO-T-mfa-067 step-up max age can be skipped")
    void skipAge() {
        StepUp.Result r = stepUp.evaluate(new StepUp.AuthContext(clock.instant().minus(Duration.ofDays(2)), Set.of("pwd", "otp")), StepUp.ACR_MFA, false);
        assertTrue(r.satisfied());
    }

    @Test
    @DisplayName("SSO-T-mfa-068 remembered device token valid until ttl, bound to user")
    void remembered() {
        RememberedDevices rd = new RememberedDevices(clock, Duration.ofDays(30));
        String t = rd.issue("alice");
        assertTrue(rd.isRemembered("alice", t));
        assertFalse(rd.isRemembered("bob", t));
        assertFalse(rd.isRemembered("alice", "bogus"));
        assertFalse(rd.isRemembered("alice", null));
        clock.advance(Duration.ofDays(30));
        assertFalse(rd.isRemembered("alice", t));
    }

    @Test
    @DisplayName("SSO-T-mfa-069 remembered device revoke and revokeAllForUser")
    void rememberedRevoke() {
        RememberedDevices rd = new RememberedDevices(clock, Duration.ofDays(30));
        String a = rd.issue("alice");
        String b = rd.issue("alice");
        rd.revoke(a);
        assertFalse(rd.isRemembered("alice", a));
        assertTrue(rd.isRemembered("alice", b));
        rd.revokeAllForUser("alice");
        assertFalse(rd.isRemembered("alice", b));
    }
}
