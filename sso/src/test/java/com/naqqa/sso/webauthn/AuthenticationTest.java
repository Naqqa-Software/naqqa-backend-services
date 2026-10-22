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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthenticationTest {
    private static String code(Executable e) {
        return assertThrows(SsoException.class, e).code();
    }

    private static TestAuthenticator registered(Fx fx, CoseAlgorithm alg, String userId) {
        TestAuthenticator a = TestAuthenticator.create(alg);
        fx.register(a, userId);
        return a;
    }

    @ParameterizedTest
    @EnumSource(CoseAlgorithm.class)
    @DisplayName("SSO-T-webauthn-180 assertion verified for every algorithm and counter updated")
    void assertEachAlg(CoseAlgorithm alg) {
        Fx fx = new Fx();
        TestAuthenticator a = registered(fx, alg, "u1");
        Fx.Assertion as = fx.assertion(a, "u1");
        as.counter = 5;
        AuthenticationResult r = as.finish();
        assertEquals("u1", r.userId());
        assertTrue(r.userVerified());
        assertFalse(r.cloneDetected());
        assertEquals(5, fx.credentials.findById(a.credentialId).orElseThrow().signCount());
        assertNotNull(fx.credentials.findById(a.credentialId).orElseThrow().lastUsedAt());
    }

    @Test
    @DisplayName("SSO-T-webauthn-181 authentication options list the user's credentials and policy UV")
    void options() {
        Fx fx = Fx.biometric();
        TestAuthenticator a = registered(fx, CoseAlgorithm.ES256, "u1");
        AuthenticationOptions o = fx.server.startAuthentication(new AuthenticationRequest("u1", null));
        assertEquals(1, o.allowCredentials().size());
        assertArrayEquals(a.credentialId, o.allowCredentials().get(0).id());
        Map<String, Object> json = Json.parseObject(o.toJson(), WebAuthnLimits.JSON);
        assertEquals("required", json.get("userVerification"));
        assertEquals("naqqa.com", json.get("rpId"));
        assertEquals(32, Bytes.b64urlDecode((String) json.get("challenge")).length);
        assertEquals(List.of("internal"), ((Map<?, ?>) ((List<?>) json.get("allowCredentials")).get(0)).get("transports"));
    }

    @Test
    @DisplayName("SSO-T-webauthn-182 discoverable usernameless flow: empty allowCredentials, userHandle resolves account")
    void discoverable() {
        Fx fx = Fx.biometric();
        TestAuthenticator a = registered(fx, CoseAlgorithm.ES256, "u1");
        CredentialRecord rec = fx.credentials.findById(a.credentialId).orElseThrow();
        Fx.Assertion as = fx.new Assertion(a, fx.server.startAuthentication(AuthenticationRequest.discoverable(null)));
        assertTrue(as.options.allowCredentials().isEmpty());
        as.userHandle = rec.userHandle();
        assertEquals("u1", as.finish().userId());
    }

    @Test
    @DisplayName("SSO-T-webauthn-183 discoverable flow without userHandle rejected")
    void discoverableMissingHandle() {
        Fx fx = new Fx();
        TestAuthenticator a = registered(fx, CoseAlgorithm.ES256, "u1");
        Fx.Assertion as = fx.new Assertion(a, fx.server.startAuthentication(AuthenticationRequest.discoverable(null)));
        assertEquals("webauthn.user_handle_missing", code(as::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-184 cross-user credential: userHandle of another account rejected")
    void crossUserHandle() {
        Fx fx = new Fx();
        TestAuthenticator a = registered(fx, CoseAlgorithm.ES256, "u1");
        TestAuthenticator b = registered(fx, CoseAlgorithm.ES256, "u2");
        Fx.Assertion as = fx.new Assertion(a, fx.server.startAuthentication(AuthenticationRequest.discoverable(null)));
        as.userHandle = fx.credentials.findById(b.credentialId).orElseThrow().userHandle();
        assertEquals("webauthn.user_handle_mismatch", code(as::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-185 cross-user credential: credential of another user against a user-bound challenge rejected")
    void crossUserChallenge() {
        Fx fx = new Fx();
        TestAuthenticator a = registered(fx, CoseAlgorithm.ES256, "u1");
        registered(fx, CoseAlgorithm.ES256, "u2");
        Fx.Assertion as = fx.assertion(a, "u2");
        assertEquals("webauthn.user_mismatch", code(as::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-186 replayed assertion rejected")
    void replay() {
        Fx fx = new Fx();
        TestAuthenticator a = registered(fx, CoseAlgorithm.ES256, "u1");
        Fx.Assertion as = fx.assertion(a, "u1");
        as.counter = 1;
        AuthenticationResponse resp = as.build();
        fx.server.finishAuthentication(resp);
        assertEquals("webauthn.challenge_invalid", code(() -> fx.server.finishAuthentication(resp)));
    }

    @Test
    @DisplayName("SSO-T-webauthn-187 registration challenge cannot be used for authentication")
    void ceremonyMixup() {
        Fx fx = new Fx();
        TestAuthenticator a = registered(fx, CoseAlgorithm.ES256, "u1");
        RegistrationOptions ro = fx.regOptions("u1");
        Fx.Assertion as = fx.assertion(a, "u1");
        as.challenge = ro.challenge();
        assertEquals("webauthn.challenge_invalid", code(as::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-188 expired authentication challenge rejected")
    void expired() {
        Fx fx = new Fx();
        TestAuthenticator a = registered(fx, CoseAlgorithm.ES256, "u1");
        Fx.Assertion as = fx.assertion(a, "u1");
        fx.clock.advance(Duration.ofMinutes(6));
        assertEquals("webauthn.challenge_invalid", code(as::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-189 wrong origin rejected")
    void wrongOrigin() {
        Fx fx = new Fx();
        TestAuthenticator a = registered(fx, CoseAlgorithm.ES256, "u1");
        Fx.Assertion as = fx.assertion(a, "u1");
        as.origin = "https://naqqa.com.attacker.io";
        assertEquals("webauthn.origin_mismatch", code(as::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-190 wrong rpId hash rejected")
    void wrongRpId() {
        Fx fx = new Fx();
        TestAuthenticator a = registered(fx, CoseAlgorithm.ES256, "u1");
        Fx.Assertion as = fx.assertion(a, "u1");
        as.rpId = "attacker.io";
        assertEquals("webauthn.rp_id_mismatch", code(as::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-191 wrong client data type rejected")
    void wrongType() {
        Fx fx = new Fx();
        TestAuthenticator a = registered(fx, CoseAlgorithm.ES256, "u1");
        Fx.Assertion as = fx.assertion(a, "u1");
        as.type = ClientData.TYPE_CREATE;
        assertEquals("webauthn.type_mismatch", code(as::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-192 user presence missing rejected")
    void noUp() {
        Fx fx = new Fx();
        TestAuthenticator a = registered(fx, CoseAlgorithm.ES256, "u1");
        Fx.Assertion as = fx.assertion(a, "u1");
        as.flags = UV;
        assertEquals("webauthn.user_not_present", code(as::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-193 user verification missing rejected under BIOMETRIC policy")
    void noUvBiometric() {
        Fx fx = Fx.biometric();
        TestAuthenticator a = registered(fx, CoseAlgorithm.ES256, "u1");
        Fx.Assertion as = fx.assertion(a, "u1");
        as.flags = UP;
        assertEquals("webauthn.user_not_verified", code(as::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-194 signature over modified clientDataJSON rejected")
    void tamperedClientData() {
        Fx fx = new Fx();
        TestAuthenticator a = registered(fx, CoseAlgorithm.ES256, "u1");
        Fx.Assertion as = fx.assertion(a, "u1");
        as.tamperClientData = true;
        assertEquals("webauthn.signature_invalid", code(as::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-195 signature by a different key rejected")
    void wrongKey() {
        Fx fx = new Fx();
        TestAuthenticator a = registered(fx, CoseAlgorithm.ES256, "u1");
        TestAuthenticator impostor = new TestAuthenticator(CoseAlgorithm.ES256, TestAuthenticator.generate(CoseAlgorithm.ES256), a.credentialId);
        Fx.Assertion as = fx.assertion(impostor, "u1");
        assertEquals("webauthn.signature_invalid", code(as::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-196 empty and garbage signatures rejected")
    void garbageSignature() {
        Fx fx = new Fx();
        TestAuthenticator a = registered(fx, CoseAlgorithm.ES256, "u1");
        Fx.Assertion as = fx.assertion(a, "u1");
        as.signatureOverride = new byte[0];
        assertEquals("webauthn.signature_invalid", code(as::finish));
        Fx.Assertion as2 = fx.assertion(a, "u1");
        as2.signatureOverride = Bytes.random(70);
        assertEquals("webauthn.signature_invalid", code(as2::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-197 counter regression flags clone and fails under default policy")
    void cloneDetected() {
        Fx fx = new Fx();
        TestAuthenticator a = registered(fx, CoseAlgorithm.ES256, "u1");
        Fx.Assertion first = fx.assertion(a, "u1");
        first.counter = 10;
        first.finish();
        Fx.Assertion cloned = fx.assertion(a, "u1");
        cloned.counter = 10;
        assertEquals("webauthn.clone_detected", code(cloned::finish));
        assertTrue(fx.credentials.findById(a.credentialId).orElseThrow().cloneDetected());
        Fx.Assertion after = fx.assertion(a, "u1");
        after.counter = 50;
        assertEquals("webauthn.credential_disabled", code(after::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-198 counter regression reported but allowed when policy does not fail on clone")
    void cloneWarnOnly() {
        Fx fx = new Fx(Fx.base().failOnClone(false).build());
        TestAuthenticator a = registered(fx, CoseAlgorithm.ES256, "u1");
        Fx.Assertion first = fx.assertion(a, "u1");
        first.counter = 10;
        first.finish();
        Fx.Assertion cloned = fx.assertion(a, "u1");
        cloned.counter = 3;
        AuthenticationResult r = cloned.finish();
        assertTrue(r.cloneDetected());
        assertEquals(10, r.credential().signCount());
        assertTrue(r.credential().cloneDetected());
    }

    @Test
    @DisplayName("SSO-T-webauthn-199 zero counters on synced passkeys are not treated as clones")
    void zeroCounters() {
        Fx fx = new Fx();
        TestAuthenticator a = registered(fx, CoseAlgorithm.ES256, "u1");
        for (int i = 0; i < 3; i++) {
            Fx.Assertion as = fx.assertion(a, "u1");
            as.counter = 0;
            assertFalse(as.finish().cloneDetected());
        }
    }

    @Test
    @DisplayName("SSO-T-webauthn-200 counter dropping to zero after nonzero flagged as clone")
    void counterToZero() {
        Fx fx = new Fx();
        TestAuthenticator a = registered(fx, CoseAlgorithm.ES256, "u1");
        Fx.Assertion first = fx.assertion(a, "u1");
        first.counter = 7;
        first.finish();
        Fx.Assertion zero = fx.assertion(a, "u1");
        zero.counter = 0;
        assertEquals("webauthn.clone_detected", code(zero::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-201 forged assertion cannot flag a credential as cloned")
    void forgedCannotFlag() {
        Fx fx = new Fx();
        TestAuthenticator a = registered(fx, CoseAlgorithm.ES256, "u1");
        Fx.Assertion first = fx.assertion(a, "u1");
        first.counter = 10;
        first.finish();
        Fx.Assertion forged = fx.assertion(a, "u1");
        forged.counter = 1;
        forged.signatureOverride = Bytes.random(70);
        assertEquals("webauthn.signature_invalid", code(forged::finish));
        assertFalse(fx.credentials.findById(a.credentialId).orElseThrow().cloneDetected());
    }

    @Test
    @DisplayName("SSO-T-webauthn-202 backup state updated, backup eligibility change rejected")
    void backupState() {
        Fx fx = new Fx();
        TestAuthenticator a = TestAuthenticator.create(CoseAlgorithm.ES256);
        Fx.Reg reg = fx.reg(a, "u1");
        reg.flags = UP | UV | AT | BE;
        reg.finish();
        Fx.Assertion as = fx.assertion(a, "u1");
        as.flags = UP | UV | BE | BS;
        assertTrue(as.finish().backupState());
        assertTrue(fx.credentials.findById(a.credentialId).orElseThrow().backupState());
        Fx.Assertion noBe = fx.assertion(a, "u1");
        noBe.flags = UP | UV;
        assertEquals("webauthn.backup_eligibility_changed", code(noBe::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-203 unknown credential id rejected")
    void unknownCredential() {
        Fx fx = new Fx();
        TestAuthenticator a = registered(fx, CoseAlgorithm.ES256, "u1");
        Fx.Assertion as = fx.assertion(a, "u1");
        as.credentialId = Bytes.random(32);
        assertEquals("webauthn.credential_unknown", code(as::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-204 attested credential data inside assertion rejected")
    void attestedInAssertion() {
        Fx fx = new Fx();
        TestAuthenticator a = registered(fx, CoseAlgorithm.ES256, "u1");
        Fx.Assertion as = fx.assertion(a, "u1");
        AuthenticationResponse good = as.build();
        byte[] ad = TestAuthenticator.authData(Fx.RP, UP | UV | AT, 1, a.attestedCredentialData(), null);
        byte[] cd = good.clientDataJSON();
        byte[] sig = a.sign(Bytes.concat(ad, com.naqqa.sso.util.Hashes.sha256(cd)));
        assertEquals("webauthn.authdata.unexpected_credential", code(() -> fx.server.finishAuthentication(
                new AuthenticationResponse(a.credentialId, cd, ad, sig, null, null))));
    }

    @Test
    @DisplayName("SSO-T-webauthn-205 assertion session binding enforced")
    void binding() {
        Fx fx = new Fx();
        TestAuthenticator a = registered(fx, CoseAlgorithm.ES256, "u1");
        Fx.Assertion as = fx.new Assertion(a, fx.server.startAuthentication(new AuthenticationRequest("u1", "sid-1")));
        as.binding = "sid-2";
        assertEquals("webauthn.binding_mismatch", code(as::finish));
        Fx.Assertion none = fx.new Assertion(a, fx.server.startAuthentication(new AuthenticationRequest("u1", "sid-1")));
        assertEquals("webauthn.binding_mismatch", code(none::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-206 assertion response JSON parsed from browser shape")
    void fromJson() {
        Fx fx = Fx.biometric();
        TestAuthenticator a = registered(fx, CoseAlgorithm.EDDSA, "u1");
        Fx.Assertion as = fx.new Assertion(a, fx.server.startAuthentication(AuthenticationRequest.discoverable(null)));
        as.userHandle = fx.credentials.findById(a.credentialId).orElseThrow().userHandle();
        AuthenticationResponse r = as.build();
        String json = "{\"id\":\"" + Bytes.b64url(a.credentialId) + "\",\"type\":\"public-key\",\"response\":{"
                + "\"clientDataJSON\":\"" + Bytes.b64url(r.clientDataJSON()) + "\",\"authenticatorData\":\""
                + Bytes.b64url(r.authenticatorData()) + "\",\"signature\":\"" + Bytes.b64url(r.signature())
                + "\",\"userHandle\":\"" + Bytes.b64url(r.userHandle()) + "\"},\"clientExtensionResults\":{}}";
        assertEquals("u1", fx.server.finishAuthentication(AuthenticationResponse.fromJson(json, null)).userId());
        assertEquals("webauthn.response.invalid", code(() -> AuthenticationResponse.fromJson(
                json.replace("\"type\"", "\"rawId\":\"AA\",\"type\""), null)));
    }

    @Test
    @DisplayName("SSO-T-webauthn-207 assertion with tampered authenticator data rejected")
    void tamperedAuthData() {
        Fx fx = new Fx();
        TestAuthenticator a = registered(fx, CoseAlgorithm.RS256, "u1");
        Fx.Assertion as = fx.assertion(a, "u1");
        as.counter = 3;
        AuthenticationResponse good = as.build();
        byte[] ad = good.authenticatorData();
        ad[36] = 9;
        assertEquals("webauthn.signature_invalid", code(() -> fx.server.finishAuthentication(
                new AuthenticationResponse(a.credentialId, good.clientDataJSON(), ad, good.signature(), null, null))));
    }

    @Test
    @DisplayName("SSO-T-webauthn-208 user without credentials gets user-bound challenge that no foreign credential satisfies")
    void unknownUserOptions() {
        Fx fx = new Fx();
        TestAuthenticator a = registered(fx, CoseAlgorithm.ES256, "u1");
        Fx.Assertion as = fx.assertion(a, "ghost");
        assertTrue(as.options.allowCredentials().isEmpty());
        assertEquals("webauthn.user_mismatch", code(as::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-209 end to end BIOMETRIC passkey: register then usernameless sign-in with UV")
    void endToEndBiometric() {
        Fx fx = Fx.biometric();
        TestAuthenticator a = TestAuthenticator.create(CoseAlgorithm.ES256);
        Fx.Reg reg = fx.reg(a, "alice");
        reg.flags = UP | UV | AT | BE | BS;
        CredentialRecord rec = reg.finish().credential();
        assertTrue(rec.uvInitialized());
        Fx.Assertion as = fx.new Assertion(a, fx.server.startAuthentication(AuthenticationRequest.discoverable("sid")));
        as.flags = UP | UV | BE | BS;
        as.userHandle = rec.userHandle();
        as.binding = "sid";
        AuthenticationResult r = as.finish();
        assertEquals("alice", r.userId());
        assertTrue(r.userVerified());
        assertEquals(0, fx.challenges.size());
    }
}
