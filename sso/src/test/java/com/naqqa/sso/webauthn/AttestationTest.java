package com.naqqa.sso.webauthn;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttestationTest {
    private static final TestCerts.Issued ROOT = TestCerts.root();
    private static final TestCerts.Issued OTHER_ROOT = TestCerts.root();

    private static String code(Executable e) {
        return assertThrows(SsoException.class, e).code();
    }

    private static Fx direct() {
        return new Fx(Fx.base().attestation(AttestationConveyance.DIRECT).build());
    }

    private static Fx trusted(TestCerts.Issued anchor) {
        return new Fx(Fx.base().attestation(AttestationConveyance.DIRECT).trustAnchors(List.of(anchor.cert()))
                .requireTrustedAttestation(true).build());
    }

    private static Map<Object, Object> stmt(Object... kv) {
        Map<Object, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static List<Object> x5c(X509Certificate... certs) {
        List<Object> out = new ArrayList<>();
        for (X509Certificate c : certs) {
            out.add(TestCerts.encoded(c));
        }
        return out;
    }

    private static Fx.Reg packedSelf(Fx fx, TestAuthenticator a) {
        Fx.Reg reg = fx.reg(a, "u1");
        reg.fmt = "packed";
        reg.attStmt = (ad, cdh) -> stmt("alg", (long) a.alg.id(), "sig", a.sign(Bytes.concat(ad, cdh)));
        return reg;
    }

    private static Fx.Reg packedFull(Fx fx, TestAuthenticator a, X509Certificate leaf, KeyPair attKey, X509Certificate... extra) {
        Fx.Reg reg = fx.reg(a, "u1");
        reg.fmt = "packed";
        List<X509Certificate> chain = new ArrayList<>(List.of(leaf));
        chain.addAll(List.of(extra));
        reg.attStmt = (ad, cdh) -> stmt("alg", -7L, "sig", TestAuthenticator.sign(CoseAlgorithm.ES256, attKey.getPrivate(),
                Bytes.concat(ad, cdh)), "x5c", x5c(chain.toArray(new X509Certificate[0])));
        return reg;
    }

    private static TestCerts.Issued packedLeaf(TestCerts.Issued issuer, byte[] aaguid) {
        return TestCerts.leaf(issuer, TestCerts.packedSubject(), TestCerts.ecKeys(), List.of(TestCerts.aaguidExt(aaguid)));
    }

    @ParameterizedTest
    @EnumSource(CoseAlgorithm.class)
    @DisplayName("SSO-T-webauthn-140 packed self attestation verified for every algorithm")
    void packedSelfEachAlg(CoseAlgorithm alg) {
        Fx fx = direct();
        RegistrationResult r = packedSelf(fx, TestAuthenticator.create(alg)).finish();
        assertEquals(AttestationType.SELF, r.attestation().type());
        assertEquals("packed", r.credential().attestationFormat());
    }

    @Test
    @DisplayName("SSO-T-webauthn-141 packed self attestation alg differing from credential alg rejected")
    void packedSelfAlgMismatch() {
        Fx fx = direct();
        TestAuthenticator a = TestAuthenticator.create(CoseAlgorithm.ES256);
        Fx.Reg reg = packedSelf(fx, a);
        reg.attStmt = (ad, cdh) -> stmt("alg", -257L, "sig", a.sign(Bytes.concat(ad, cdh)));
        assertEquals("webauthn.attestation.alg_mismatch", code(reg::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-142 packed self attestation with signature over other data rejected")
    void packedSelfBadSig() {
        Fx fx = direct();
        TestAuthenticator a = TestAuthenticator.create(CoseAlgorithm.ES256);
        Fx.Reg reg = packedSelf(fx, a);
        reg.attStmt = (ad, cdh) -> stmt("alg", -7L, "sig", a.sign(Bytes.concat(ad, new byte[32])));
        assertEquals("webauthn.attestation.signature", code(reg::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-143 packed full attestation with x5c verified as basic, untrusted without anchors")
    void packedFull() {
        Fx fx = direct();
        TestAuthenticator a = TestAuthenticator.create(CoseAlgorithm.EDDSA);
        a.aaguid = Bytes.random(16);
        TestCerts.Issued leaf = packedLeaf(ROOT, a.aaguid);
        RegistrationResult r = packedFull(fx, a, leaf.cert(), leaf.keys()).finish();
        assertEquals(AttestationType.BASIC, r.attestation().type());
        assertFalse(r.attestation().trusted());
        assertEquals(1, r.attestation().chain().size());
    }

    @Test
    @DisplayName("SSO-T-webauthn-144 packed x5c chain validated to configured trust anchor")
    void packedTrusted() {
        Fx fx = trusted(ROOT);
        TestAuthenticator a = TestAuthenticator.create(CoseAlgorithm.ES256);
        TestCerts.Issued leaf = packedLeaf(ROOT, a.aaguid);
        RegistrationResult r = packedFull(fx, a, leaf.cert(), leaf.keys()).finish();
        assertTrue(r.attestation().trusted());
        assertTrue(r.credential().attestationTrusted());
    }

    @Test
    @DisplayName("SSO-T-webauthn-145 packed x5c from untrusted root rejected when trust required")
    void packedUntrusted() {
        Fx fx = trusted(OTHER_ROOT);
        TestAuthenticator a = TestAuthenticator.create(CoseAlgorithm.ES256);
        TestCerts.Issued leaf = packedLeaf(ROOT, a.aaguid);
        assertEquals("webauthn.attestation.untrusted", code(packedFull(fx, a, leaf.cert(), leaf.keys())::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-146 chain with intermediate validated through PKIX")
    void intermediate() {
        Fx fx = trusted(ROOT);
        KeyPair ik = TestCerts.ecKeys();
        byte[] iname = TestCerts.name("RO", "Naqqa Test", null, "Naqqa Test Intermediate");
        X509Certificate inter = TestCerts.build(iname, ik.getPublic(), ROOT.cert().getSubjectX500Principal().getEncoded(),
                ROOT.keys().getPrivate(), true, List.of());
        TestAuthenticator a = TestAuthenticator.create(CoseAlgorithm.ES256);
        TestCerts.Issued leaf = packedLeaf(new TestCerts.Issued(inter, ik), a.aaguid);
        assertTrue(packedFull(fx, a, leaf.cert(), leaf.keys(), inter).finish().attestation().trusted());
        TestAuthenticator b = TestAuthenticator.create(CoseAlgorithm.ES256);
        TestCerts.Issued leafB = packedLeaf(new TestCerts.Issued(inter, ik), b.aaguid);
        assertEquals("webauthn.attestation.untrusted", code(packedFull(fx, b, leafB.cert(), leafB.keys())::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-147 attestation chain checked against clock: expired certificates untrusted")
    void expiredChain() {
        Fx fx = trusted(ROOT);
        fx.clock.advance(Duration.ofDays(365 * 10));
        TestAuthenticator a = TestAuthenticator.create(CoseAlgorithm.ES256);
        TestCerts.Issued leaf = packedLeaf(ROOT, a.aaguid);
        assertEquals("webauthn.attestation.untrusted", code(packedFull(fx, a, leaf.cert(), leaf.keys())::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-148 packed certificate without OU Authenticator Attestation rejected")
    void packedBadSubject() {
        Fx fx = direct();
        TestAuthenticator a = TestAuthenticator.create(CoseAlgorithm.ES256);
        TestCerts.Issued leaf = TestCerts.leaf(ROOT, TestCerts.name("RO", "Naqqa", "Other", "X"), TestCerts.ecKeys(), List.of());
        assertEquals("webauthn.attestation.certificate", code(packedFull(fx, a, leaf.cert(), leaf.keys())::finish));
        TestCerts.Issued noC = TestCerts.leaf(ROOT, TestCerts.name(null, "Naqqa", "Authenticator Attestation", "X"), TestCerts.ecKeys(), List.of());
        assertEquals("webauthn.attestation.certificate", code(packedFull(fx, TestAuthenticator.create(CoseAlgorithm.ES256), noC.cert(), noC.keys())::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-149 packed certificate aaguid extension mismatch rejected")
    void packedAaguidMismatch() {
        Fx fx = direct();
        TestAuthenticator a = TestAuthenticator.create(CoseAlgorithm.ES256);
        a.aaguid = Bytes.random(16);
        TestCerts.Issued leaf = packedLeaf(ROOT, Bytes.random(16));
        assertEquals("webauthn.attestation.aaguid_mismatch", code(packedFull(fx, a, leaf.cert(), leaf.keys())::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-150 packed x5c alg not matching certificate key rejected")
    void packedX5cAlgMismatch() {
        Fx fx = direct();
        TestAuthenticator a = TestAuthenticator.create(CoseAlgorithm.ES256);
        TestCerts.Issued leaf = packedLeaf(ROOT, a.aaguid);
        Fx.Reg reg = fx.reg(a, "u1");
        reg.fmt = "packed";
        reg.attStmt = (ad, cdh) -> stmt("alg", -257L, "sig", TestAuthenticator.sign(CoseAlgorithm.ES256, leaf.keys().getPrivate(),
                Bytes.concat(ad, cdh)), "x5c", x5c(leaf.cert()));
        assertEquals("webauthn.attestation.alg_mismatch", code(reg::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-151 packed CA certificate used as attestation leaf rejected")
    void packedCaLeaf() {
        Fx fx = direct();
        TestAuthenticator a = TestAuthenticator.create(CoseAlgorithm.ES256);
        KeyPair k = TestCerts.ecKeys();
        X509Certificate ca = TestCerts.build(TestCerts.packedSubject(), k.getPublic(), ROOT.cert().getSubjectX500Principal().getEncoded(),
                ROOT.keys().getPrivate(), true, List.of());
        assertEquals("webauthn.attestation.certificate", code(packedFull(fx, a, ca, k)::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-152 packed ECDAA and unknown statement keys rejected")
    void packedEcdaa() {
        Fx fx = direct();
        TestAuthenticator a = TestAuthenticator.create(CoseAlgorithm.ES256);
        Fx.Reg reg = packedSelf(fx, a);
        reg.attStmt = (ad, cdh) -> stmt("alg", -7L, "sig", a.sign(Bytes.concat(ad, cdh)), "ecdaaKeyId", new byte[]{1});
        assertEquals("webauthn.attestation.unsupported", code(reg::finish));
        Fx.Reg reg2 = packedSelf(fx, a);
        reg2.attStmt = (ad, cdh) -> stmt("alg", -7L, "sig", a.sign(Bytes.concat(ad, cdh)), "extra", 1L);
        assertEquals("webauthn.attestation.invalid", code(reg2::finish));
    }

    private static Fx.Reg u2f(Fx fx, TestAuthenticator a, TestCerts.Issued att, boolean wrongRp) {
        Fx.Reg reg = fx.reg(a, "u1");
        reg.fmt = "fido-u2f";
        reg.attStmt = (ad, cdh) -> {
            ECPublicKey ec = (ECPublicKey) a.keys.getPublic();
            byte[] pk = Bytes.concat(new byte[]{4}, CoseKey.fixed(ec.getW().getAffineX(), 32), CoseKey.fixed(ec.getW().getAffineY(), 32));
            byte[] rpHash = wrongRp ? Hashes.sha256(Bytes.utf8("evil.com")) : Hashes.sha256(Bytes.utf8(Fx.RP));
            byte[] data = Bytes.concat(new byte[]{0}, rpHash, cdh, a.credentialId, pk);
            return stmt("sig", TestAuthenticator.sign(CoseAlgorithm.ES256, att.keys().getPrivate(), data), "x5c", x5c(att.cert()));
        };
        return reg;
    }

    @Test
    @DisplayName("SSO-T-webauthn-153 fido-u2f attestation verified")
    void u2fOk() {
        Fx fx = direct();
        TestCerts.Issued att = TestCerts.leaf(ROOT, TestCerts.name(null, null, null, "U2F"), TestCerts.ecKeys(), List.of());
        RegistrationResult r = u2f(fx, TestAuthenticator.create(CoseAlgorithm.ES256), att, false).finish();
        assertEquals(AttestationType.BASIC, r.attestation().type());
        assertEquals("fido-u2f", r.attestation().format());
    }

    @Test
    @DisplayName("SSO-T-webauthn-154 fido-u2f signature over other rpId rejected")
    void u2fWrongRp() {
        Fx fx = direct();
        TestCerts.Issued att = TestCerts.leaf(ROOT, TestCerts.name(null, null, null, "U2F"), TestCerts.ecKeys(), List.of());
        assertEquals("webauthn.attestation.signature", code(u2f(fx, TestAuthenticator.create(CoseAlgorithm.ES256), att, true)::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-155 fido-u2f with non P-256 credential rejected")
    void u2fWrongCredentialAlg() {
        Fx fx = direct();
        TestAuthenticator a = TestAuthenticator.create(CoseAlgorithm.EDDSA);
        TestCerts.Issued att = TestCerts.leaf(ROOT, TestCerts.name(null, null, null, "U2F"), TestCerts.ecKeys(), List.of());
        Fx.Reg reg = fx.reg(a, "u1");
        reg.fmt = "fido-u2f";
        reg.attStmt = (ad, cdh) -> stmt("sig", new byte[]{1}, "x5c", x5c(att.cert()));
        assertEquals("webauthn.attestation.alg_mismatch", code(reg::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-156 fido-u2f with more than one certificate rejected")
    void u2fChain() {
        Fx fx = direct();
        TestCerts.Issued att = TestCerts.leaf(ROOT, TestCerts.name(null, null, null, "U2F"), TestCerts.ecKeys(), List.of());
        Fx.Reg reg = fx.reg(TestAuthenticator.create(CoseAlgorithm.ES256), "u1");
        reg.fmt = "fido-u2f";
        reg.attStmt = (ad, cdh) -> stmt("sig", new byte[]{1}, "x5c", x5c(att.cert(), ROOT.cert()));
        assertEquals("webauthn.attestation.certificate", code(reg::finish));
    }

    private static Fx.Reg apple(Fx fx, TestAuthenticator a, boolean wrongNonce, KeyPair certKey, boolean withExt) {
        Fx.Reg reg = fx.reg(a, "u1");
        reg.fmt = "apple";
        reg.attStmt = (ad, cdh) -> {
            byte[] nonce = wrongNonce ? Bytes.random(32) : Hashes.sha256(Bytes.concat(ad, cdh));
            List<TestCerts.Ext> exts = withExt ? List.of(TestCerts.appleNonceExt(nonce)) : List.of();
            TestCerts.Issued leaf = TestCerts.leaf(ROOT, TestCerts.name("US", "Apple Inc.", null, "credential"), certKey, exts);
            return stmt("x5c", x5c(leaf.cert(), ROOT.cert()));
        };
        return reg;
    }

    @Test
    @DisplayName("SSO-T-webauthn-157 apple anonymous attestation verified via nonce extension")
    void appleOk() {
        Fx fx = new Fx(Fx.base().attestation(AttestationConveyance.DIRECT).trustAnchors(List.of(ROOT.cert())).build());
        TestAuthenticator a = TestAuthenticator.create(CoseAlgorithm.ES256);
        RegistrationResult r = apple(fx, a, false, a.keys, true).finish();
        assertEquals(AttestationType.ANONCA, r.attestation().type());
        assertTrue(r.attestation().trusted());
    }

    @Test
    @DisplayName("SSO-T-webauthn-158 apple nonce mismatch or missing extension rejected")
    void appleNonce() {
        Fx fx = direct();
        TestAuthenticator a = TestAuthenticator.create(CoseAlgorithm.ES256);
        assertEquals("webauthn.attestation.apple_nonce", code(apple(fx, a, true, a.keys, true)::finish));
        TestAuthenticator b = TestAuthenticator.create(CoseAlgorithm.ES256);
        assertEquals("webauthn.attestation.apple_nonce", code(apple(fx, b, false, b.keys, false)::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-159 apple certificate key differing from credential key rejected")
    void appleKeyMismatch() {
        Fx fx = direct();
        TestAuthenticator a = TestAuthenticator.create(CoseAlgorithm.ES256);
        assertEquals("webauthn.attestation.key_mismatch", code(apple(fx, a, false, TestCerts.ecKeys(), true)::finish));
    }

    private static Fx.Reg android(Fx fx, TestAuthenticator a, boolean wrongChallenge, boolean allApps) {
        return android(fx, a, wrongChallenge, allApps, true, true);
    }

    private static Fx.Reg android(Fx fx, TestAuthenticator a, boolean wrongChallenge, boolean allApps, boolean purpose, boolean origin) {
        Fx.Reg reg = fx.reg(a, "u1");
        reg.fmt = "android-key";
        reg.attStmt = (ad, cdh) -> {
            byte[] ch = wrongChallenge ? Bytes.random(32) : cdh;
            TestCerts.Issued leaf = TestCerts.leaf(ROOT, TestCerts.name(null, null, null, "Android Keystore Key"), a.keys,
                    List.of(TestCerts.androidKeyExt(ch, allApps, purpose, origin)));
            return stmt("alg", -7L, "sig", a.sign(Bytes.concat(ad, cdh)), "x5c", x5c(leaf.cert()));
        };
        return reg;
    }

    @Test
    @DisplayName("SSO-T-webauthn-160 android-key attestation verified")
    void androidOk() {
        Fx fx = direct();
        RegistrationResult r = android(fx, TestAuthenticator.create(CoseAlgorithm.ES256), false, false).finish();
        assertEquals("android-key", r.attestation().format());
        assertEquals(AttestationType.BASIC, r.attestation().type());
    }

    @Test
    @DisplayName("SSO-T-webauthn-161 android-key attestation challenge mismatch rejected")
    void androidChallenge() {
        assertEquals("webauthn.attestation.android_challenge",
                code(android(direct(), TestAuthenticator.create(CoseAlgorithm.ES256), true, false)::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-162 android-key allApplications authorization rejected")
    void androidAllApps() {
        assertEquals("webauthn.attestation.android_all_applications",
                code(android(direct(), TestAuthenticator.create(CoseAlgorithm.ES256), false, true)::finish));
    }

    private static Fx.Reg tpm(Fx fx) {
        TestCerts.Issued leaf = TestCerts.leaf(ROOT, TestCerts.name(null, null, null, "TPM"), TestCerts.ecKeys(), List.of());
        Fx.Reg reg = fx.reg(TestAuthenticator.create(CoseAlgorithm.RS256), "u1");
        reg.fmt = "tpm";
        reg.attStmt = (ad, cdh) -> stmt("ver", "2.0", "alg", -257L, "x5c", x5c(leaf.cert()), "sig", new byte[]{1},
                "certInfo", new byte[]{1}, "pubArea", new byte[]{1});
        return reg;
    }

    @Test
    @DisplayName("SSO-T-webauthn-163 tpm attestation rejected unless policy allows unverified")
    void tpmPolicy() {
        assertEquals("webauthn.attestation.unsupported", code(tpm(direct())::finish));
        Fx lax = new Fx(Fx.base().attestation(AttestationConveyance.DIRECT).allowUnverifiedAttestation(true).build());
        assertEquals(AttestationType.UNVERIFIED, tpm(lax).finish().attestation().type());
    }

    @Test
    @DisplayName("SSO-T-webauthn-164 self and none attestation rejected when trusted attestation required")
    void requireTrustedRejectsSelf() {
        Fx fx = trusted(ROOT);
        assertEquals("webauthn.attestation.untrusted", code(packedSelf(fx, TestAuthenticator.create(CoseAlgorithm.ES256))::finish));
        assertEquals("webauthn.attestation.none_not_allowed", code(fx.reg(TestAuthenticator.create(CoseAlgorithm.ES256), "u1")::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-165 malformed certificate bytes in x5c rejected")
    void garbageCert() {
        Fx fx = direct();
        TestAuthenticator a = TestAuthenticator.create(CoseAlgorithm.ES256);
        Fx.Reg reg = fx.reg(a, "u1");
        reg.fmt = "packed";
        reg.attStmt = (ad, cdh) -> stmt("alg", -7L, "sig", new byte[]{1}, "x5c", List.of(new byte[]{0x30, 0x03, 1, 2, 3}));
        assertEquals("webauthn.attestation.certificate", code(reg::finish));
        Fx.Reg reg2 = fx.reg(TestAuthenticator.create(CoseAlgorithm.ES256), "u1");
        reg2.fmt = "packed";
        reg2.attStmt = (ad, cdh) -> stmt("alg", -7L, "sig", new byte[]{1}, "x5c", List.of());
        assertEquals("webauthn.attestation.invalid", code(reg2::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-166 android-safetynet treated as unverified format")
    void safetynet() {
        Fx fx = direct();
        Fx.Reg reg = fx.reg(TestAuthenticator.create(CoseAlgorithm.ES256), "u1");
        reg.fmt = "android-safetynet";
        reg.attStmt = (ad, cdh) -> stmt("ver", "1", "response", new byte[]{1});
        assertEquals("webauthn.attestation.unsupported", code(reg::finish));
    }

    @Test
    @DisplayName("SSO-T-webauthn-212 android-key KeyDescription without origin or without purpose rejected (WebAuthn L3 8.4.1)")
    void androidMissingOriginOrPurpose() {
        assertEquals("webauthn.attestation.android_origin",
                code(android(direct(), TestAuthenticator.create(CoseAlgorithm.ES256), false, false, true, false)::finish));
        assertEquals("webauthn.attestation.android_purpose",
                code(android(direct(), TestAuthenticator.create(CoseAlgorithm.ES256), false, false, false, true)::finish));
    }
}
