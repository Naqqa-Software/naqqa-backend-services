package com.naqqa.sso.vectors;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.json.Json;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;
import com.naqqa.sso.webauthn.AttestationType;
import com.naqqa.sso.webauthn.AuthenticationResponse;
import com.naqqa.sso.webauthn.Cbor;
import com.naqqa.sso.webauthn.Ceremony;
import com.naqqa.sso.webauthn.CoseAlgorithm;
import com.naqqa.sso.webauthn.CoseKey;
import com.naqqa.sso.webauthn.CredentialRecord;
import com.naqqa.sso.webauthn.InMemoryChallengeStore;
import com.naqqa.sso.webauthn.InMemoryCredentialStore;
import com.naqqa.sso.webauthn.RegistrationResponse;
import com.naqqa.sso.webauthn.RegistrationResult;
import com.naqqa.sso.webauthn.AuthenticationResult;
import com.naqqa.sso.webauthn.UserVerification;
import com.naqqa.sso.webauthn.WebAuthnChallenge;
import com.naqqa.sso.webauthn.WebAuthnPolicy;
import com.naqqa.sso.webauthn.WebAuthnServer;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.naqqa.sso.vectors.VectorIo.m;

final class WebauthnVectors {
    static final String RP = "naqqa.com";
    static final String ORIGIN = "https://app.naqqa.com";
    static final Instant NOW = Instant.parse("2026-10-15T10:00:00Z");
    private static final int UP = 0x01;
    private static final int UV = 0x04;
    private static final int BE = 0x08;
    private static final int BS = 0x10;
    private static final int AT = 0x40;

    private final Map<String, Map<String, Object>> prior;
    private final List<Object> cases = new ArrayList<>();
    private final Map<CoseAlgorithm, KeyPair> pairs = new EnumMap<>(CoseAlgorithm.class);

    WebauthnVectors(Map<String, Object> existing) {
        prior = VectorIo.casesByName(existing);
    }

    private KeyPair pair(CoseAlgorithm alg) {
        return pairs.computeIfAbsent(alg, a -> {
            try {
                return switch (a) {
                    case ES256 -> {
                        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
                        g.initialize(new ECGenParameterSpec("secp256r1"));
                        yield g.generateKeyPair();
                    }
                    case EDDSA -> KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
                    default -> {
                        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
                        g.initialize(2048);
                        yield g.generateKeyPair();
                    }
                };
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static byte[] sign(CoseAlgorithm alg, KeyPair kp, byte[] data) {
        try {
            Signature s = Signature.getInstance(switch (alg) {
                case ES256 -> "SHA256withECDSA";
                case EDDSA -> "Ed25519";
                default -> "SHA256withRSA";
            });
            s.initSign(kp.getPrivate());
            s.update(data);
            return s.sign();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] seed(String label) {
        return Hashes.sha256(Bytes.utf8("naqqa-sso-vector:" + label));
    }

    private static String b(byte[] v) {
        return Bytes.b64url(v);
    }

    private static byte[] authData(String rpId, int flags, long counter, byte[] attested) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(Hashes.sha256(Bytes.utf8(rpId)));
        out.write(flags);
        out.write((int) (counter >>> 24));
        out.write((int) (counter >>> 16));
        out.write((int) (counter >>> 8));
        out.write((int) counter);
        if (attested != null) {
            out.writeBytes(attested);
        }
        return out.toByteArray();
    }

    private static byte[] clientData(String type, byte[] challenge, String origin) {
        return Bytes.utf8("{\"type\":\"" + type + "\",\"challenge\":\"" + Bytes.b64url(challenge) + "\",\"origin\":\"" + origin
                + "\",\"crossOrigin\":false}");
    }

    static final class Sc {
        String ceremony;
        CoseAlgorithm alg = CoseAlgorithm.ES256;
        String uv = "preferred";
        List<String> allowed = List.of("EDDSA", "ES256", "RS256");
        boolean failOnClone = true;
        String type;
        String origin = ORIGIN;
        String rpIdHashed = RP;
        int flags = UP | UV;
        long counter;
        long storedCounter;
        boolean storedBackupEligible;
        String fmt = "none";
        boolean badAttestationSig;
        boolean tamperAssertionSig;
        boolean challengeMismatch;
        boolean unknownCredential;
        boolean wrongUserHandle;
        long challengeAgeSeconds;
        long nowOffsetSeconds;
    }

    private Map<String, Object> input(String name, Sc sc) {
        KeyPair kp = pair(sc.alg);
        byte[] challenge = seed(name + ":challenge");
        byte[] credId = seed(name + ":credential");
        byte[] handle = seed(name + ":handle");
        byte[] cose = CoseKey.of(kp.getPublic(), sc.alg).encoded();
        Instant issued = NOW.minusSeconds(sc.challengeAgeSeconds);
        byte[] presented = sc.challengeMismatch ? seed(name + ":other-challenge") : challenge;
        Map<String, Object> policy = m("rpId", RP, "origins", List.of(ORIGIN), "userVerification", sc.uv,
                "allowedAlgorithms", sc.allowed, "failOnClone", sc.failOnClone);
        Map<String, Object> in = m("ceremony", sc.ceremony, "policy", policy, "nowIso", NOW.plusSeconds(sc.nowOffsetSeconds).toString(),
                "challengeIssuedAtIso", issued.toString(), "challenge", b(challenge));
        if (sc.ceremony.equals("registration")) {
            byte[] attested = Bytes.concat(new byte[16], new byte[]{(byte) (credId.length >>> 8), (byte) credId.length}, credId, cose);
            byte[] ad = authData(sc.rpIdHashed, sc.flags | AT, sc.counter, attested);
            byte[] cd = clientData(sc.type != null ? sc.type : "webauthn.create", presented, sc.origin);
            Map<Object, Object> stmt = new LinkedHashMap<>();
            if (sc.fmt.equals("packed")) {
                byte[] sig = sign(sc.alg, kp, Bytes.concat(ad, Hashes.sha256(cd)));
                if (sc.badAttestationSig) {
                    sig = sig.clone();
                    sig[sig.length - 1] ^= 0x01;
                }
                stmt.put("alg", (long) sc.alg.id());
                stmt.put("sig", sig);
            }
            Map<Object, Object> att = new LinkedHashMap<>();
            att.put("fmt", sc.fmt);
            att.put("attStmt", stmt);
            att.put("authData", ad);
            in.put("user", m("id", "user-" + name, "name", "alex@naqqa.com", "displayName", "Alex", "handle", b(handle)));
            in.put("response", m("id", b(credId), "clientDataJSON", b(cd), "attestationObject", b(Cbor.encode(att)), "transports", List.of("internal")));
        } else {
            byte[] respId = sc.unknownCredential ? seed(name + ":other-credential") : credId;
            byte[] ad = authData(sc.rpIdHashed, sc.flags, sc.counter, null);
            byte[] cd = clientData(sc.type != null ? sc.type : "webauthn.get", presented, sc.origin);
            byte[] sig = sign(sc.alg, kp, Bytes.concat(ad, Hashes.sha256(cd)));
            if (sc.tamperAssertionSig) {
                sig = sig.clone();
                sig[sig.length - 1] ^= 0x01;
            }
            in.put("stored", m("credentialId", b(credId), "userId", "user-" + name, "userHandle", b(handle), "publicKeyCose", b(cose),
                    "algorithm", sc.alg.name(), "signCount", sc.storedCounter, "backupEligible", sc.storedBackupEligible, "backupState", false));
            byte[] respHandle = sc.wrongUserHandle ? seed(name + ":other-handle") : null;
            in.put("response", m("id", b(respId), "clientDataJSON", b(cd), "authenticatorData", b(ad), "signature", b(sig),
                    "userHandle", respHandle == null ? null : b(respHandle)));
        }
        return in;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> evaluate(Map<String, Object> in) {
        Map<String, Object> p = Json.asObject(in.get("policy"));
        List<CoseAlgorithm> allowed = new ArrayList<>();
        for (Object a : Json.asList(p.get("allowedAlgorithms"))) {
            allowed.add(CoseAlgorithm.valueOf((String) a));
        }
        WebAuthnPolicy policy = WebAuthnPolicy.builder((String) p.get("rpId"), "Naqqa")
                .origins((List<String>) (List<?>) Json.asList(p.get("origins")))
                .userVerification(UserVerification.valueOf(((String) p.get("userVerification")).toUpperCase()))
                .allowedAlgorithms(allowed).failOnClone((Boolean) p.get("failOnClone")).build();
        Clock clock = Clock.fixed(Instant.parse((String) in.get("nowIso")), ZoneOffset.UTC);
        InMemoryChallengeStore challenges = new InMemoryChallengeStore(clock);
        InMemoryCredentialStore credentials = new InMemoryCredentialStore();
        WebAuthnServer server = new WebAuthnServer(policy, credentials, challenges, clock);
        Instant expires = Instant.parse((String) in.get("challengeIssuedAtIso")).plus(policy.challengeTtl());
        byte[] challenge = Bytes.b64urlDecode((String) in.get("challenge"));
        Map<String, Object> r = Json.asObject(in.get("response"));
        try {
            if ("registration".equals(in.get("ceremony"))) {
                Map<String, Object> u = Json.asObject(in.get("user"));
                challenges.save(new WebAuthnChallenge(challenge, Ceremony.REGISTRATION, Bytes.b64urlDecode((String) u.get("handle")),
                        (String) u.get("id"), (String) u.get("name"), (String) u.get("displayName"), null, expires));
                List<String> transports = (List<String>) (List<?>) Json.asList(r.get("transports"));
                RegistrationResult res = server.finishRegistration(new RegistrationResponse(Bytes.b64urlDecode((String) r.get("id")),
                        Bytes.b64urlDecode((String) r.get("clientDataJSON")), Bytes.b64urlDecode((String) r.get("attestationObject")), transports, null));
                CredentialRecord c = res.credential();
                return m("ok", true, "credentialId", b(c.credentialId()), "algorithm", c.algorithm().name(), "signCount", c.signCount(),
                        "userVerified", res.userVerified(), "backupEligible", c.backupEligible(), "attestationFormat", res.attestation().format(),
                        "attestationType", res.attestation().type().name(), "publicKeyCose", b(c.publicKeyCose()));
            }
            Map<String, Object> s = Json.asObject(in.get("stored"));
            byte[] handle = Bytes.b64urlDecode((String) s.get("userHandle"));
            credentials.create(new CredentialRecord(Bytes.b64urlDecode((String) s.get("credentialId")), handle, (String) s.get("userId"),
                    Bytes.b64urlDecode((String) s.get("publicKeyCose")), CoseAlgorithm.valueOf((String) s.get("algorithm")),
                    (Long) s.get("signCount"), true, (Boolean) s.get("backupEligible"), (Boolean) s.get("backupState"), List.of("internal"),
                    new byte[16], "none", AttestationType.NONE, false, NOW.minusSeconds(86400), null, false));
            challenges.save(new WebAuthnChallenge(challenge, Ceremony.AUTHENTICATION, null, (String) s.get("userId"), null, null, null, expires));
            Object uh = r.get("userHandle");
            AuthenticationResult res = server.finishAuthentication(new AuthenticationResponse(Bytes.b64urlDecode((String) r.get("id")),
                    Bytes.b64urlDecode((String) r.get("clientDataJSON")), Bytes.b64urlDecode((String) r.get("authenticatorData")),
                    Bytes.b64urlDecode((String) r.get("signature")), uh == null ? null : Bytes.b64urlDecode((String) uh), null));
            return m("ok", true, "userId", res.userId(), "userVerified", res.userVerified(), "cloneDetected", res.cloneDetected(),
                    "signCount", res.credential().signCount());
        } catch (SsoException e) {
            return m("ok", false, "error", e.code());
        }
    }

    private void add(String name, String description, Sc sc, String wantError) {
        Map<String, Object> in = prior.containsKey(name) ? Json.asObject(prior.get(name).get("input")) : input(name, sc);
        Map<String, Object> out = evaluate(in);
        String got = Boolean.TRUE.equals(out.get("ok")) ? null : (String) out.get("error");
        if (!Objects.equals(wantError, got)) {
            throw new AssertionError("webauthn vector " + name + " intended " + wantError + " but implementation gave " + got);
        }
        cases.add(VectorIo.docCase(name, description, in, out));
    }

    private static Sc reg() {
        Sc s = new Sc();
        s.ceremony = "registration";
        return s;
    }

    private static Sc auth() {
        Sc s = new Sc();
        s.ceremony = "authentication";
        s.storedCounter = 5;
        s.counter = 6;
        return s;
    }

    Map<String, Object> build() {
        CoseAlgorithm[] algs = {CoseAlgorithm.ES256, CoseAlgorithm.RS256, CoseAlgorithm.EDDSA};
        for (CoseAlgorithm a : algs) {
            String n = a.name().toLowerCase();
            Sc none = reg();
            none.alg = a;
            add("register-none-" + n, "Registration with attestation none, " + a + " credential.", none, null);
            Sc packed = reg();
            packed.alg = a;
            packed.fmt = "packed";
            add("register-packed-self-" + n, "Registration with packed self attestation, " + a + " credential.", packed, null);
            Sc au = auth();
            au.alg = a;
            add("authenticate-" + n, "Assertion for an " + a + " credential, counter 5 to 6.", au, null);
        }
        Sc sync = auth();
        sync.flags = UP | UV | BE | BS;
        sync.storedBackupEligible = true;
        add("authenticate-synced-passkey", "Backup eligible credential with backup state set.", sync, null);
        Sc zero = auth();
        zero.storedCounter = 0;
        zero.counter = 0;
        add("authenticate-counter-unsupported", "Authenticator without counter: 0 to 0 is not a clone signal.", zero, null);

        Sc badOrigin = reg();
        badOrigin.origin = "https://evil.example.com";
        add("register-bad-origin", "clientDataJSON origin is not in the allowed list.", badOrigin, "webauthn.origin_mismatch");
        Sc badOriginAuth = auth();
        badOriginAuth.origin = "https://evil.example.com";
        add("authenticate-bad-origin", "Assertion from an origin that is not allowed.", badOriginAuth, "webauthn.origin_mismatch");
        Sc wrongTypeReg = reg();
        wrongTypeReg.type = "webauthn.get";
        add("register-wrong-type", "Registration response with type webauthn.get.", wrongTypeReg, "webauthn.type_mismatch");
        Sc wrongTypeAuth = auth();
        wrongTypeAuth.type = "webauthn.create";
        add("authenticate-wrong-type", "Assertion with type webauthn.create.", wrongTypeAuth, "webauthn.type_mismatch");
        Sc uvReg = reg();
        uvReg.uv = "required";
        uvReg.flags = UP;
        add("register-uv-missing-biometric-policy", "Policy requires user verification, UV flag absent.", uvReg, "webauthn.user_not_verified");
        Sc uvAuth = auth();
        uvAuth.uv = "required";
        uvAuth.flags = UP;
        add("authenticate-uv-missing-biometric-policy", "Policy requires user verification, UV flag absent.", uvAuth, "webauthn.user_not_verified");
        Sc uvOk = auth();
        uvOk.uv = "required";
        add("authenticate-uv-present-biometric-policy", "Policy requires user verification, UV flag present.", uvOk, null);
        Sc uvPreferred = auth();
        uvPreferred.flags = UP;
        add("authenticate-uv-absent-preferred-policy", "Policy prefers UV, absent flag is tolerated.", uvPreferred, null);
        Sc noUp = auth();
        noUp.flags = UV;
        add("authenticate-user-not-present", "UP flag not set.", noUp, "webauthn.user_not_present");
        Sc regress = auth();
        regress.storedCounter = 10;
        regress.counter = 10;
        add("authenticate-counter-regression", "Counter did not increase: possible clone, policy fails closed.", regress, "webauthn.clone_detected");
        Sc regressLow = auth();
        regressLow.storedCounter = 10;
        regressLow.counter = 3;
        add("authenticate-counter-lower", "Counter went backwards.", regressLow, "webauthn.clone_detected");
        Sc regressTolerated = auth();
        regressTolerated.storedCounter = 10;
        regressTolerated.counter = 9;
        regressTolerated.failOnClone = false;
        add("authenticate-counter-regression-tolerant-policy", "Same regression with failOnClone off: succeeds and reports cloneDetected.", regressTolerated, null);
        Sc badSig = auth();
        badSig.tamperAssertionSig = true;
        add("authenticate-bad-signature", "Last byte of the assertion signature flipped.", badSig, "webauthn.signature_invalid");
        Sc badRp = auth();
        badRp.rpIdHashed = "evil.example.com";
        add("authenticate-rp-id-hash-mismatch", "authenticatorData rpIdHash is for another RP.", badRp, "webauthn.rp_id_mismatch");
        Sc badRpReg = reg();
        badRpReg.rpIdHashed = "evil.example.com";
        add("register-rp-id-hash-mismatch", "authenticatorData rpIdHash is for another RP.", badRpReg, "webauthn.rp_id_mismatch");
        Sc unknown = auth();
        unknown.unknownCredential = true;
        add("authenticate-unknown-credential", "Credential id is not registered.", unknown, "webauthn.credential_unknown");
        Sc handle = auth();
        handle.wrongUserHandle = true;
        add("authenticate-user-handle-mismatch", "userHandle belongs to another user.", handle, "webauthn.user_handle_mismatch");
        Sc beChange = auth();
        beChange.flags = UP | UV | BE;
        add("authenticate-backup-eligibility-changed", "BE flag set but the stored credential is not backup eligible.", beChange, "webauthn.backup_eligibility_changed");
        Sc chal = reg();
        chal.challengeMismatch = true;
        add("register-challenge-mismatch", "Challenge in clientDataJSON was never issued.", chal, "webauthn.challenge_invalid");
        Sc chalAuth = auth();
        chalAuth.challengeMismatch = true;
        add("authenticate-challenge-mismatch", "Challenge in clientDataJSON was never issued.", chalAuth, "webauthn.challenge_invalid");
        Sc expired = auth();
        expired.challengeAgeSeconds = 301;
        add("authenticate-challenge-expired", "Challenge issued 301 seconds ago, ttl is 300.", expired, "webauthn.challenge_invalid");
        Sc badAtt = reg();
        badAtt.fmt = "packed";
        badAtt.badAttestationSig = true;
        add("register-packed-bad-signature", "Self attestation signature flipped.", badAtt, "webauthn.attestation.signature");
        Sc algNot = reg();
        algNot.alg = CoseAlgorithm.RS256;
        algNot.allowed = List.of("ES256");
        add("register-algorithm-not-allowed", "RS256 credential while the policy allows only ES256.", algNot, "webauthn.alg_not_allowed");

        return m("description", "WebAuthn registration and authentication responses (attestation none and packed self) with expected outcomes. "
                        + "Binary values are base64url without padding. Challenges are single use and valid 300 seconds from challengeIssuedAtIso.",
                "version", 1, "cases", cases);
    }
}
