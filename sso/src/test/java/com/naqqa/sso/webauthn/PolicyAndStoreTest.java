package com.naqqa.sso.webauthn;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.util.Bytes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyAndStoreTest {
    private static String code(WebAuthnPolicy.Builder b) {
        return assertThrows(SsoException.class, b::build).code();
    }

    @Test
    @DisplayName("SSO-T-webauthn-080 BIOMETRIC preset: platform, UV required, resident key required, client-device hint")
    void biometricPreset() {
        WebAuthnPolicy p = WebAuthnPreset.BIOMETRIC.policy("naqqa.com", "Naqqa", List.of("https://naqqa.com"));
        assertEquals(AuthenticatorAttachment.PLATFORM, p.attachment());
        assertEquals(UserVerification.REQUIRED, p.userVerification());
        assertEquals(ResidentKey.REQUIRED, p.residentKey());
        assertEquals(List.of(PublicKeyCredentialHint.CLIENT_DEVICE), p.hints());
        assertTrue(p.userVerificationRequired());
    }

    @Test
    @DisplayName("SSO-T-webauthn-081 SECURITY_KEY preset: cross-platform, security-key hint, direct attestation")
    void securityKeyPreset() {
        WebAuthnPolicy p = WebAuthnPreset.SECURITY_KEY.policy("naqqa.com", "Naqqa", List.of("https://naqqa.com"));
        assertEquals(AuthenticatorAttachment.CROSS_PLATFORM, p.attachment());
        assertEquals(ResidentKey.DISCOURAGED, p.residentKey());
        assertEquals(AttestationConveyance.DIRECT, p.attestation());
        assertEquals(List.of(PublicKeyCredentialHint.SECURITY_KEY), p.hints());
        assertFalse(p.userVerificationRequired());
    }

    @Test
    @DisplayName("SSO-T-webauthn-082 origins outside rpId, plain http, paths and empty list rejected")
    void originValidation() {
        assertEquals("webauthn.policy.origins", code(Fx.base().origins(List.of("https://evil.com"))));
        assertEquals("webauthn.policy.origins", code(Fx.base().origins(List.of("http://app.naqqa.com"))));
        assertEquals("webauthn.policy.origins", code(Fx.base().origins(List.of("https://app.naqqa.com/login"))));
        assertEquals("webauthn.policy.origins", code(Fx.base().origins(List.of("https://evilnaqqa.com"))));
        assertEquals("webauthn.policy.origins", code(Fx.base().origins(List.of())));
        assertEquals("webauthn.policy.origins", code(Fx.base().origins(List.of("android:apk-key-hash:"))));
        WebAuthnPolicy local = WebAuthnPolicy.builder("localhost", "Dev").origins(List.of("http://localhost:4200")).build();
        assertEquals("localhost", local.rpId());
    }

    @Test
    @DisplayName("SSO-T-webauthn-083 invalid rpId, ttl above 5 minutes, bad limits rejected")
    void policyValidation() {
        assertEquals("webauthn.policy.rp_id", code(WebAuthnPolicy.builder("Naqqa.com", "N").origins(List.of("https://naqqa.com"))));
        assertEquals("webauthn.policy.rp_id", code(WebAuthnPolicy.builder("https://naqqa.com", "N").origins(List.of("https://naqqa.com"))));
        assertEquals("webauthn.policy.challenge_ttl", code(Fx.base().challengeTtl(Duration.ofMinutes(6))));
        assertEquals("webauthn.policy.max_credentials", code(Fx.base().maxCredentialsPerUser(0)));
        assertEquals("webauthn.policy.algorithms", code(Fx.base().allowedAlgorithms(List.of())));
        assertEquals("webauthn.policy.trust_anchors", code(Fx.base().requireTrustedAttestation(true)));
        assertEquals("webauthn.policy.timeout", code(Fx.base().timeout(Duration.ofSeconds(1))));
    }

    @Test
    @DisplayName("SSO-T-webauthn-084 challenge store is single use")
    void challengeSingleUse() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        InMemoryChallengeStore s = new InMemoryChallengeStore(clock);
        byte[] v = Bytes.random(32);
        s.save(new WebAuthnChallenge(v, Ceremony.AUTHENTICATION, null, "u", null, null, null, clock.instant().plusSeconds(300)));
        assertTrue(s.consume(v).isPresent());
        assertTrue(s.consume(v).isEmpty());
    }

    @Test
    @DisplayName("SSO-T-webauthn-085 challenge store expires entries after TTL and purges them")
    void challengeExpiry() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        InMemoryChallengeStore s = new InMemoryChallengeStore(clock);
        byte[] v = Bytes.random(32);
        s.save(new WebAuthnChallenge(v, Ceremony.AUTHENTICATION, null, "u", null, null, null, clock.instant().plusSeconds(300)));
        s.save(new WebAuthnChallenge(Bytes.random(32), Ceremony.AUTHENTICATION, null, "u", null, null, null, clock.instant().plusSeconds(300)));
        clock.advance(Duration.ofSeconds(300));
        assertTrue(s.consume(v).isEmpty());
        s.purge();
        assertEquals(0, s.size());
    }

    @Test
    @DisplayName("SSO-T-webauthn-086 challenge store capacity bounded")
    void challengeCapacity() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        InMemoryChallengeStore s = new InMemoryChallengeStore(clock, 2);
        for (int i = 0; i < 2; i++) {
            s.save(new WebAuthnChallenge(Bytes.random(32), Ceremony.REGISTRATION, null, "u", null, null, null, clock.instant().plusSeconds(60)));
        }
        assertEquals("webauthn.challenge.capacity", assertThrows(SsoException.class, () -> s.save(new WebAuthnChallenge(
                Bytes.random(32), Ceremony.REGISTRATION, null, "u", null, null, null, clock.instant().plusSeconds(60)))).code());
    }

    @Test
    @DisplayName("SSO-T-webauthn-087 credential store rejects duplicate ids and stale updates")
    void credentialStore() {
        InMemoryCredentialStore s = new InMemoryCredentialStore();
        TestAuthenticator a = TestAuthenticator.create(CoseAlgorithm.ES256);
        CredentialRecord r = new CredentialRecord(a.credentialId, Bytes.random(64), "u1", a.coseKey().encoded(), CoseAlgorithm.ES256,
                5, true, false, false, List.of(), null, "none", AttestationType.NONE, false, Instant.EPOCH, null, false);
        s.create(r);
        assertEquals("webauthn.credential_exists", assertThrows(SsoException.class, () -> s.create(r)).code());
        CredentialRecord next = r.withUsage(6, false, true, Instant.EPOCH.plusSeconds(1));
        assertTrue(s.update(r, next));
        assertFalse(s.update(r, r.withUsage(7, false, true, Instant.EPOCH.plusSeconds(2))));
        assertEquals(6, s.findById(a.credentialId).orElseThrow().signCount());
        assertEquals(1, s.findByUserId("u1").size());
        assertEquals(1, s.findByUserHandle(r.userHandle()).size());
        assertTrue(s.delete(a.credentialId));
        assertTrue(s.findById(a.credentialId).isEmpty());
    }

    @Test
    @DisplayName("SSO-T-webauthn-088 credential record rejects backup state without eligibility and returns copies")
    void credentialRecord() {
        TestAuthenticator a = TestAuthenticator.create(CoseAlgorithm.ES256);
        assertEquals("webauthn.credential.invalid", assertThrows(SsoException.class, () -> new CredentialRecord(a.credentialId,
                Bytes.random(64), "u1", a.coseKey().encoded(), CoseAlgorithm.ES256, 0, true, false, true, null, null, "none",
                AttestationType.NONE, false, Instant.EPOCH, null, false)).code());
        CredentialRecord r = new CredentialRecord(a.credentialId, Bytes.random(64), "u1", a.coseKey().encoded(), CoseAlgorithm.ES256,
                0, true, false, false, null, null, "none", AttestationType.NONE, false, Instant.EPOCH, null, false);
        r.credentialId()[0] ^= 1;
        assertEquals(a.credentialId[0], r.credentialId()[0]);
        assertNull(r.lastUsedAt());
    }
}
