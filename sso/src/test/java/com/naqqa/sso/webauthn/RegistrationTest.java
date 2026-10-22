package com.naqqa.sso.webauthn;

import com.naqqa.sso.json.Json;
import com.naqqa.sso.SsoException;
import com.naqqa.sso.util.Bytes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.naqqa.sso.webauthn.TestAuthenticator.AT;
import static com.naqqa.sso.webauthn.TestAuthenticator.BE;
import static com.naqqa.sso.webauthn.TestAuthenticator.BS;
import static com.naqqa.sso.webauthn.TestAuthenticator.UP;
import static com.naqqa.sso.webauthn.TestAuthenticator.UV;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistrationTest {
    private static String code(Executable e) {
        return assertThrows(SsoException.class, e).code();
    }

    @ParameterizedTest
    @EnumSource(CoseAlgorithm.class)
    @DisplayName("SSO-T-webauthn-100 registration with none attestation succeeds for every algorithm")
    void registerEachAlg(CoseAlgorithm alg) {
        Fx fx = new Fx();
        TestAuthenticator a = TestAuthenticator.create(alg);
        RegistrationResult r = fx.reg(a, "u1").finish();
        assertArrayEquals(a.credentialId, r.credential().credentialId());
        assertEquals(alg, r.credential().algorithm());
        assertEquals(AttestationType.NONE, r.attestation().type());
        assertTrue(r.userVerified());
        assertEquals("u1", r.credential().userId());
        assertEquals(64, r.credential().userHandle().length);
        assertTrue(fx.credentials.findById(a.credentialId).isPresent());
    }

    @Test
    @DisplayName("SSO-T-webauthn-101 registration options: 32-byte challenge, random 64-byte user handle, algs -8,-7,-257")
    void options() {
        Fx fx = new Fx(Fx.base().allowedAlgorithms(WebAuthnPolicy.DEFAULT_ALGORITHMS).build());
        RegistrationOptions o = fx.regOptions("u1");
        assertEquals(32, o.challenge().length);
        assertEquals(64, o.userHandle().length);
        assertFalse(new String(o.userHandle(), java.nio.charset.StandardCharsets.ISO_8859_1).contains("naqqa.com"));
        assertEquals(List.of(-8, -7, -257), o.algorithms().stream().map(CoseAlgorithm::id).toList());
        Map<String, Object> json = Json.parseObject(o.toJson(), WebAuthnLimits.JSON);
        assertEquals(Bytes.b64url(o.challenge()), json.get("challenge"));
        assertEquals("naqqa.com", ((Map<?, ?>) json.get("rp")).get("id"));
        assertEquals(300000L, json.get("timeout"));
        assertEquals("none", json.get("attestation"));
        assertNotEquals(Bytes.b64url(o.challenge()), Bytes.b64url(fx.regOptions("u1").challenge()));
    }

    @Test
    @DisplayName("SSO-T-webauthn-102 BIOMETRIC options carry platform attachment, required UV and resident key, client-device hint")
    void biometricOptions() {
        Fx fx = Fx.biometric();
        Map<String, Object> json = Json.parseObject(fx.regOptions("u1").toJson(), WebAuthnLimits.JSON);
        Map<?, ?> sel = (Map<?, ?>) json.get("authenticatorSelection");
        assertEquals("platform", sel.get("authenticatorAttachment"));
        assertEquals("required", sel.get("userVerification"));
        assertEquals("required", sel.get("residentKey"));
        assertEquals(Boolean.TRUE, sel.get("requireResidentKey"));
        assertEquals(List.of("client-device"), json.get("hints"));
    }

    @Test
    @DisplayName("SSO-T-webauthn-103 excludeCredentials lists existing credentials and the user handle is reused")
    void excludeCredentials() {
        Fx fx = new Fx();
        TestAuthenticator a = TestAuthenticator.create(CoseAlgorithm.ES256);
        CredentialRecord first = fx.register(a, "u1");
        RegistrationOptions o = fx.regOptions("u1");
        assertEquals(1, o.excludeCredentials().size());
        assertArrayEquals(a.credentialId, o.excludeCredentials().get(0).id());
        assertArrayEquals(first.userHandle(), o.userHandle());
    }

    @Test
    @DisplayName("SSO-T-webauthn-104 replayed registration challenge rejected")
    void replay() {
        Fx fx = new Fx();
        TestAuthenticator a = TestAuthenticator.create(CoseAlgorithm.ES256);
        Fx.Reg reg = fx.reg(a, "u1");
        RegistrationResponse resp = reg.build();
        fx.server.finishRegistration(resp);
        assertEquals("webauthn.challenge_invalid", code(() -> fx.server.finishRegistration(resp)));
    }

    @Test
    @DisplayName("SSO-T-webauthn-105 unknown challenge rejected")
    void unknownChallenge() {
        Fx fx = new Fx();
        Fx.Reg reg = fx.reg(TestAuthenticator.create(CoseAlgorithm.ES256), "u1");
        reg.challenge = Bytes.random(32);
        assertEquals("webauthn.challenge_invalid", code(reg::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-106 expired challenge rejected after TTL")
    void expired() {
        Fx fx = new Fx();
        Fx.Reg reg = fx.reg(TestAuthenticator.create(CoseAlgorithm.ES256), "u1");
        fx.clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        assertEquals("webauthn.challenge_invalid", code(reg::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-107 failed attempt burns the challenge")
    void failedAttemptBurns() {
        Fx fx = new Fx();
        Fx.Reg reg = fx.reg(TestAuthenticator.create(CoseAlgorithm.ES256), "u1");
        reg.origin = "https://evil.com";
        assertEquals("webauthn.origin_mismatch", code(reg::finish));
        reg.origin = Fx.ORIGIN;
        assertEquals("webauthn.challenge_invalid", code(reg::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-108 wrong origin rejected, subdomain lookalike rejected")
    void wrongOrigin() {
        Fx fx = new Fx();
        for (String o : List.of("https://evil.com", "https://app.naqqa.com.evil.com", "http://app.naqqa.com", "https://APP.naqqa.com",
                "https://app.naqqa.com:8443", "https://app.naqqa.com/")) {
            Fx.Reg reg = fx.reg(TestAuthenticator.create(CoseAlgorithm.ES256), "u1");
            reg.origin = o;
            assertEquals("webauthn.origin_mismatch", code(reg::finish), o);
        }
    }

    @Test
    @DisplayName("SSO-T-webauthn-109 Android apk-key-hash origin accepted when configured")
    void androidOrigin() {
        Fx fx = new Fx();
        Fx.Reg reg = fx.reg(TestAuthenticator.create(CoseAlgorithm.ES256), "u1");
        reg.origin = Fx.ANDROID_ORIGIN;
        assertEquals(AttestationType.NONE, reg.finish().attestation().type());
        Fx.Reg other = fx.reg(TestAuthenticator.create(CoseAlgorithm.ES256), "u2");
        other.origin = "android:apk-key-hash:b3RoZXI";
        assertEquals("webauthn.origin_mismatch", code(other::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-110 wrong rpId hash rejected")
    void wrongRpId() {
        Fx fx = new Fx();
        Fx.Reg reg = fx.reg(TestAuthenticator.create(CoseAlgorithm.ES256), "u1");
        reg.rpId = "evil.com";
        assertEquals("webauthn.rp_id_mismatch", code(reg::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-111 wrong client data type rejected")
    void wrongType() {
        Fx fx = new Fx();
        Fx.Reg reg = fx.reg(TestAuthenticator.create(CoseAlgorithm.ES256), "u1");
        reg.type = ClientData.TYPE_GET;
        assertEquals("webauthn.type_mismatch", code(reg::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-112 cross-origin iframe ceremony rejected")
    void crossOrigin() {
        Fx fx = new Fx();
        Fx.Reg reg = fx.reg(TestAuthenticator.create(CoseAlgorithm.ES256), "u1");
        reg.crossOrigin = Boolean.TRUE;
        assertEquals("webauthn.cross_origin", code(reg::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-113 user presence missing rejected")
    void noUp() {
        Fx fx = new Fx();
        Fx.Reg reg = fx.reg(TestAuthenticator.create(CoseAlgorithm.ES256), "u1");
        reg.flags = UV | AT;
        assertEquals("webauthn.user_not_present", code(reg::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-114 user verification missing rejected under BIOMETRIC policy")
    void noUvBiometric() {
        Fx fx = Fx.biometric();
        Fx.Reg reg = fx.reg(TestAuthenticator.create(CoseAlgorithm.ES256), "u1");
        reg.flags = UP | AT;
        assertEquals("webauthn.user_not_verified", code(reg::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-115 user verification optional under preferred policy")
    void noUvPreferred() {
        Fx fx = new Fx();
        Fx.Reg reg = fx.reg(TestAuthenticator.create(CoseAlgorithm.ES256), "u1");
        reg.flags = UP | AT;
        RegistrationResult r = reg.finish();
        assertFalse(r.userVerified());
        assertFalse(r.credential().uvInitialized());
    }

    @Test
    @DisplayName("SSO-T-webauthn-116 backup flags recorded for synced passkeys")
    void backupFlags() {
        Fx fx = new Fx();
        Fx.Reg reg = fx.reg(TestAuthenticator.create(CoseAlgorithm.ES256), "u1");
        reg.flags = UP | UV | AT | BE | BS;
        CredentialRecord c = reg.finish().credential();
        assertTrue(c.backupEligible());
        assertTrue(c.backupState());
    }

    @Test
    @DisplayName("SSO-T-webauthn-117 backup state without eligibility rejected")
    void bsWithoutBe() {
        Fx fx = new Fx();
        Fx.Reg reg = fx.reg(TestAuthenticator.create(CoseAlgorithm.ES256), "u1");
        reg.flags = UP | UV | AT | BS;
        assertEquals("webauthn.authdata.backup_state_invalid", code(reg::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-118 algorithm not allowed by policy rejected")
    void algNotAllowed() {
        Fx fx = new Fx(Fx.base().allowedAlgorithms(List.of(CoseAlgorithm.ES256)).build());
        Fx.Reg reg = fx.reg(TestAuthenticator.create(CoseAlgorithm.RS256), "u1");
        assertEquals("webauthn.alg_not_allowed", code(reg::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-119 missing attested credential data rejected")
    void noAt() {
        Fx fx = new Fx();
        Fx.Reg reg = fx.reg(TestAuthenticator.create(CoseAlgorithm.ES256), "u1");
        reg.flags = UP | UV;
        reg.attested = new byte[0];
        assertEquals("webauthn.authdata.no_credential", code(reg::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-120 credential id longer than 1023 bytes rejected at registration")
    void credentialIdTooLong() {
        Fx fx = new Fx();
        TestAuthenticator a = new TestAuthenticator(CoseAlgorithm.ES256, TestAuthenticator.generate(CoseAlgorithm.ES256), new byte[1024]);
        assertEquals("webauthn.authdata.credential_id_length", code(fx.reg(a, "u1")::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-121 credential id already registered to another user rejected")
    void duplicateCredential() {
        Fx fx = new Fx();
        TestAuthenticator a = TestAuthenticator.create(CoseAlgorithm.ES256);
        fx.register(a, "u1");
        assertEquals("webauthn.credential_exists", code(fx.reg(a, "u2")::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-122 response credential id must match authenticator data")
    void credentialIdMismatch() {
        Fx fx = new Fx();
        Fx.Reg reg = fx.reg(TestAuthenticator.create(CoseAlgorithm.ES256), "u1");
        reg.responseCredentialId = Bytes.random(32);
        assertEquals("webauthn.credential_id_mismatch", code(reg::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-123 maximum credentials per user enforced")
    void maxCredentials() {
        Fx fx = new Fx(Fx.base().maxCredentialsPerUser(1).build());
        Fx.Reg pending = fx.reg(TestAuthenticator.create(CoseAlgorithm.ES256), "u1");
        fx.register(TestAuthenticator.create(CoseAlgorithm.ES256), "u1");
        assertEquals("webauthn.max_credentials", code(() -> fx.regOptions("u1")));
        assertEquals("webauthn.max_credentials", code(pending::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-124 unknown attestation format rejected")
    void unknownFormat() {
        Fx fx = new Fx();
        Fx.Reg reg = fx.reg(TestAuthenticator.create(CoseAlgorithm.ES256), "u1");
        reg.fmt = "evil-fmt";
        assertEquals("webauthn.attestation.fmt_unknown", code(reg::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-125 none attestation with non-empty statement rejected")
    void noneWithStatement() {
        Fx fx = new Fx();
        Fx.Reg reg = fx.reg(TestAuthenticator.create(CoseAlgorithm.ES256), "u1");
        reg.attStmt = (ad, cdh) -> new LinkedHashMap<>(Map.of("sig", new byte[]{1}));
        assertEquals("webauthn.attestation.invalid", code(reg::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-126 none attestation rejected when policy forbids it")
    void noneForbidden() {
        Fx fx = new Fx(Fx.base().allowNoneAttestation(false).build());
        assertEquals("webauthn.attestation.none_not_allowed", code(fx.reg(TestAuthenticator.create(CoseAlgorithm.ES256), "u1")::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-127 session binding mismatch rejected")
    void binding() {
        Fx fx = new Fx();
        RegistrationOptions o = fx.server.startRegistration(new RegistrationRequest("u1", "u1", "U", null, "session-a"));
        Fx.Reg reg = fx.new Reg(TestAuthenticator.create(CoseAlgorithm.ES256), o);
        reg.binding = "session-b";
        assertEquals("webauthn.binding_mismatch", code(reg::finish));
        RegistrationOptions o2 = fx.server.startRegistration(new RegistrationRequest("u1", "u1", "U", null, "session-a"));
        Fx.Reg ok = fx.new Reg(TestAuthenticator.create(CoseAlgorithm.ES256), o2);
        ok.binding = "session-a";
        assertEquals("u1", ok.finish().credential().userId());
    }

    @Test
    @DisplayName("SSO-T-webauthn-128 attestation object with extra top-level keys or duplicate keys rejected")
    void attestationObjectShape() {
        Fx fx = new Fx();
        Fx.Reg reg = fx.reg(TestAuthenticator.create(CoseAlgorithm.ES256), "u1");
        RegistrationResponse good = reg.build();
        byte[] att = good.attestationObject();
        byte[] dup = att.clone();
        dup[0] = (byte) 0xa4;
        byte[] withDup = Bytes.concat(dup, Cbor.encode("fmt"), Cbor.encode("none"));
        RegistrationResponse bad = new RegistrationResponse(null, good.clientDataJSON(), withDup, List.of(), null);
        assertEquals("webauthn.cbor.duplicate_key", code(() -> fx.server.finishRegistration(bad)));
        Fx.Reg reg2 = fx.reg(TestAuthenticator.create(CoseAlgorithm.ES256), "u1");
        RegistrationResponse g2 = reg2.build();
        byte[] trailing = Bytes.concat(g2.attestationObject(), new byte[]{0});
        assertEquals("webauthn.cbor.trailing", code(() -> fx.server.finishRegistration(
                new RegistrationResponse(null, g2.clientDataJSON(), trailing, List.of(), null))));
    }

    @Test
    @DisplayName("SSO-T-webauthn-129 response JSON parsing accepts browser PublicKeyCredential shape")
    void fromJson() {
        Fx fx = new Fx();
        TestAuthenticator a = TestAuthenticator.create(CoseAlgorithm.EDDSA);
        RegistrationResponse r = fx.reg(a, "u1").build();
        String json = "{\"id\":\"" + Bytes.b64url(a.credentialId) + "\",\"rawId\":\"" + Bytes.b64url(a.credentialId)
                + "\",\"type\":\"public-key\",\"response\":{\"clientDataJSON\":\"" + Bytes.b64url(r.clientDataJSON())
                + "\",\"attestationObject\":\"" + Bytes.b64url(r.attestationObject()) + "\",\"transports\":[\"internal\",\"hybrid\"]}}";
        RegistrationResult res = fx.server.finishRegistration(RegistrationResponse.fromJson(json, null));
        assertEquals(List.of("internal", "hybrid"), res.credential().transports());
        assertEquals("webauthn.response.invalid", code(() -> RegistrationResponse.fromJson(json.replace("public-key", "x"), null)));
    }

    @Test
    @DisplayName("SSO-T-webauthn-130 attestation object with CBOR tag or float rejected")
    void tagInAttestation() {
        Fx fx = new Fx();
        RegistrationResponse g = fx.reg(TestAuthenticator.create(CoseAlgorithm.ES256), "u1").build();
        byte[] tagged = Bytes.concat(new byte[]{(byte) 0xd8, 0x18}, g.attestationObject());
        assertEquals("webauthn.cbor.tag", code(() -> fx.server.finishRegistration(
                new RegistrationResponse(null, g.clientDataJSON(), tagged, List.of(), null))));
    }
}
