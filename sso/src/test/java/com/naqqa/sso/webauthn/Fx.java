package com.naqqa.sso.webauthn;

import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

final class Fx {
    static final String RP = "naqqa.com";
    static final String ORIGIN = "https://app.naqqa.com";
    static final String ANDROID_ORIGIN = "android:apk-key-hash:Q0hBTkdFTUU";

    final MutableClock clock = new MutableClock(Instant.parse("2026-10-15T10:00:00Z"));
    final InMemoryCredentialStore credentials = new InMemoryCredentialStore();
    final InMemoryChallengeStore challenges = new InMemoryChallengeStore(clock);
    final WebAuthnPolicy policy;
    final WebAuthnServer server;

    Fx(WebAuthnPolicy policy) {
        this.policy = policy;
        this.server = new WebAuthnServer(policy, credentials, challenges, clock);
    }

    Fx() {
        this(base().build());
    }

    static WebAuthnPolicy.Builder base() {
        return WebAuthnPolicy.builder(RP, "Naqqa").origins(List.of(ORIGIN, ANDROID_ORIGIN))
                .allowedAlgorithms(List.of(CoseAlgorithm.EDDSA, CoseAlgorithm.ES256, CoseAlgorithm.RS256, CoseAlgorithm.PS256));
    }

    static Fx biometric() {
        return new Fx(WebAuthnPreset.BIOMETRIC.builder(RP, "Naqqa", List.of(ORIGIN)).build());
    }

    RegistrationOptions regOptions(String userId) {
        return server.startRegistration(new RegistrationRequest(userId, userId + "@naqqa.com", "User " + userId, null, null));
    }

    Reg reg(TestAuthenticator a, String userId) {
        return new Reg(a, regOptions(userId));
    }

    CredentialRecord register(TestAuthenticator a, String userId) {
        return reg(a, userId).finish().credential();
    }

    Assertion assertion(TestAuthenticator a, String userId) {
        return new Assertion(a, server.startAuthentication(new AuthenticationRequest(userId, null)));
    }

    final class Reg {
        final TestAuthenticator a;
        final RegistrationOptions options;
        String type = ClientData.TYPE_CREATE;
        byte[] challenge;
        String origin = ORIGIN;
        Boolean crossOrigin;
        String rpId = RP;
        int flags = TestAuthenticator.UP | TestAuthenticator.UV | TestAuthenticator.AT;
        String fmt = "none";
        BiFunction<byte[], byte[], Map<Object, Object>> attStmt = (ad, cdh) -> new LinkedHashMap<>();
        byte[] attested;
        byte[] responseCredentialId;
        String binding;
        byte[] clientDataOverride;

        Reg(TestAuthenticator a, RegistrationOptions options) {
            this.a = a;
            this.options = options;
            this.challenge = options.challenge();
        }

        RegistrationResponse build() {
            byte[] cd = clientDataOverride != null ? clientDataOverride
                    : TestAuthenticator.clientData(type, challenge, origin, crossOrigin);
            byte[] ad = TestAuthenticator.authData(rpId, flags, a.counter,
                    attested != null ? attested : a.attestedCredentialData(), null);
            Map<Object, Object> stmt = attStmt.apply(ad, Hashes.sha256(cd));
            return new RegistrationResponse(responseCredentialId, cd, TestAuthenticator.attestationObject(fmt, stmt, ad),
                    List.of("internal"), binding);
        }

        RegistrationResult finish() {
            return server.finishRegistration(build());
        }
    }

    final class Assertion {
        final TestAuthenticator a;
        final AuthenticationOptions options;
        String type = ClientData.TYPE_GET;
        byte[] challenge;
        String origin = ORIGIN;
        String rpId = RP;
        int flags = TestAuthenticator.UP | TestAuthenticator.UV;
        long counter;
        byte[] userHandle;
        byte[] credentialId;
        String binding;
        boolean tamperClientData;
        byte[] signatureOverride;

        Assertion(TestAuthenticator a, AuthenticationOptions options) {
            this.a = a;
            this.options = options;
            this.challenge = options.challenge();
            this.counter = a.counter;
            this.credentialId = a.credentialId;
        }

        AuthenticationResponse build() {
            byte[] cd = TestAuthenticator.clientData(type, challenge, origin, null);
            byte[] ad = TestAuthenticator.authData(rpId, flags, counter, null, null);
            byte[] sig = signatureOverride != null ? signatureOverride : a.sign(Bytes.concat(ad, Hashes.sha256(cd)));
            if (tamperClientData) {
                cd = TestAuthenticator.clientData(type, challenge, origin, Boolean.FALSE);
            }
            return new AuthenticationResponse(credentialId, cd, ad, sig, userHandle, binding);
        }

        AuthenticationResult finish() {
            return server.finishAuthentication(build());
        }
    }
}
