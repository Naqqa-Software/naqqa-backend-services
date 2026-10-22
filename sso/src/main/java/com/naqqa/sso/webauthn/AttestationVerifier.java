package com.naqqa.sso.webauthn;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;

import java.io.ByteArrayInputStream;
import java.security.GeneralSecurityException;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class AttestationVerifier {
    public static final int MAX_CHAIN = 5;
    static final String OID_FIDO_AAGUID = "1.3.6.1.4.1.45724.1.1.4";
    static final String OID_APPLE_NONCE = "1.2.840.113635.100.8.2";
    static final String OID_ANDROID_KEY = "1.3.6.1.4.1.11129.2.1.17";
    private static final String OID_C = "2.5.4.6";
    private static final String OID_O = "2.5.4.10";
    private static final String OID_OU = "2.5.4.11";
    private static final String OID_CN = "2.5.4.3";
    private static final int ANDROID_ALL_APPLICATIONS = 600;
    private static final int ANDROID_ORIGIN = 702;
    private static final int ANDROID_PURPOSE = 1;

    private final WebAuthnPolicy policy;
    private final Clock clock;

    public AttestationVerifier(WebAuthnPolicy policy, Clock clock) {
        this.policy = policy;
        this.clock = clock;
    }

    public AttestationResult verify(String fmt, Map<Object, Object> attStmt, AuthenticatorData authData, byte[] clientDataHash) {
        AttestedCredentialData acd = authData.attestedCredentialData();
        if (fmt == null || attStmt == null || acd == null) {
            throw new SsoException("webauthn.attestation.invalid");
        }
        AttestationResult r = switch (fmt) {
            case "none" -> none(attStmt);
            case "packed" -> packed(attStmt, authData, acd, clientDataHash);
            case "fido-u2f" -> u2f(attStmt, authData, acd, clientDataHash);
            case "apple" -> apple(attStmt, authData, acd, clientDataHash);
            case "android-key" -> androidKey(attStmt, authData, acd, clientDataHash);
            case "tpm" -> tpm(attStmt);
            case "android-safetynet" -> safetynet(attStmt);
            default -> throw new SsoException("webauthn.attestation.fmt_unknown");
        };
        return applyTrust(r);
    }

    private AttestationResult applyTrust(AttestationResult r) {
        switch (r.type()) {
            case NONE -> {
                if (!policy.allowNoneAttestation() || policy.requireTrustedAttestation()) {
                    throw new SsoException("webauthn.attestation.none_not_allowed");
                }
                return r;
            }
            case UNVERIFIED -> {
                if (!policy.allowUnverifiedAttestation() || policy.requireTrustedAttestation()) {
                    throw new SsoException("webauthn.attestation.unsupported");
                }
                return r;
            }
            case SELF -> {
                if (policy.requireTrustedAttestation()) {
                    throw new SsoException("webauthn.attestation.untrusted");
                }
                return r;
            }
            default -> {
                boolean trusted = !policy.trustAnchors().isEmpty() && chainTrusted(r.chain());
                if (policy.requireTrustedAttestation() && !trusted) {
                    throw new SsoException("webauthn.attestation.untrusted");
                }
                return new AttestationResult(r.format(), r.type(), r.chain(), trusted);
            }
        }
    }

    private boolean chainTrusted(List<X509Certificate> chain) {
        if (chain.isEmpty()) {
            return false;
        }
        try {
            Set<TrustAnchor> anchors = new HashSet<>();
            for (X509Certificate a : policy.trustAnchors()) {
                anchors.add(new TrustAnchor(a, null));
            }
            List<X509Certificate> path = new ArrayList<>(chain);
            if (path.size() > 1 && policy.trustAnchors().contains(path.get(path.size() - 1))) {
                path.remove(path.size() - 1);
            }
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            CertPath cp = cf.generateCertPath(path);
            PKIXParameters params = new PKIXParameters(anchors);
            params.setRevocationEnabled(false);
            params.setDate(Date.from(clock.instant()));
            CertPathValidator.getInstance("PKIX").validate(cp, params);
            return true;
        } catch (GeneralSecurityException | RuntimeException e) {
            return false;
        }
    }

    private static void allowedKeys(Map<Object, Object> attStmt, Set<String> allowed) {
        for (Object k : attStmt.keySet()) {
            if (!(k instanceof String s) || !allowed.contains(s)) {
                throw new SsoException("webauthn.attestation.invalid");
            }
        }
    }

    private static AttestationResult none(Map<Object, Object> attStmt) {
        if (!attStmt.isEmpty()) {
            throw new SsoException("webauthn.attestation.invalid");
        }
        return new AttestationResult("none", AttestationType.NONE, List.of(), false);
    }

    private static byte[] sig(Map<Object, Object> attStmt) {
        if (attStmt.get("sig") instanceof byte[] s && s.length > 0) {
            return s;
        }
        throw new SsoException("webauthn.attestation.invalid");
    }

    private static CoseAlgorithm alg(Map<Object, Object> attStmt) {
        if (attStmt.get("alg") instanceof Long a) {
            return CoseAlgorithm.of(a);
        }
        throw new SsoException("webauthn.attestation.invalid");
    }

    static List<X509Certificate> x5c(Map<Object, Object> attStmt) {
        if (!(attStmt.get("x5c") instanceof List<?> list) || list.isEmpty() || list.size() > MAX_CHAIN) {
            throw new SsoException("webauthn.attestation.invalid");
        }
        List<X509Certificate> out = new ArrayList<>();
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            for (Object o : list) {
                if (!(o instanceof byte[] der)) {
                    throw new SsoException("webauthn.attestation.invalid");
                }
                out.add((X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der)));
            }
        } catch (GeneralSecurityException | ClassCastException e) {
            throw new SsoException("webauthn.attestation.certificate");
        }
        return out;
    }

    private static byte[] signed(AuthenticatorData authData, byte[] clientDataHash) {
        return Bytes.concat(authData.raw(), clientDataHash);
    }

    private static AttestationResult packed(Map<Object, Object> attStmt, AuthenticatorData authData,
                                            AttestedCredentialData acd, byte[] cdh) {
        if (attStmt.containsKey("ecdaaKeyId")) {
            throw new SsoException("webauthn.attestation.unsupported");
        }
        allowedKeys(attStmt, Set.of("alg", "sig", "x5c"));
        CoseAlgorithm alg = alg(attStmt);
        byte[] sig = sig(attStmt);
        byte[] data = signed(authData, cdh);
        if (attStmt.containsKey("x5c")) {
            List<X509Certificate> chain = x5c(attStmt);
            X509Certificate leaf = chain.get(0);
            if (!CoseKey.keyMatches(alg, leaf.getPublicKey())) {
                throw new SsoException("webauthn.attestation.alg_mismatch");
            }
            if (!alg.verify(leaf.getPublicKey(), data, sig)) {
                throw new SsoException("webauthn.attestation.signature");
            }
            checkPackedCertificate(leaf, acd.aaguid());
            return new AttestationResult("packed", AttestationType.BASIC, chain, false);
        }
        CoseKey key = acd.credentialPublicKey();
        if (alg != key.algorithm()) {
            throw new SsoException("webauthn.attestation.alg_mismatch");
        }
        if (!key.verify(data, sig)) {
            throw new SsoException("webauthn.attestation.signature");
        }
        return new AttestationResult("packed", AttestationType.SELF, List.of(), false);
    }

    static void checkPackedCertificate(X509Certificate cert, byte[] aaguid) {
        if (cert.getVersion() != 3 || cert.getBasicConstraints() != -1) {
            throw new SsoException("webauthn.attestation.certificate");
        }
        Map<String, String> subject = subject(cert);
        String c = subject.get(OID_C);
        if (c == null || c.length() != 2 || subject.get(OID_O) == null || subject.get(OID_CN) == null
                || !"Authenticator Attestation".equals(subject.get(OID_OU))) {
            throw new SsoException("webauthn.attestation.certificate");
        }
        byte[] ext = cert.getExtensionValue(OID_FIDO_AAGUID);
        if (ext != null) {
            Set<String> critical = cert.getCriticalExtensionOIDs();
            if (critical != null && critical.contains(OID_FIDO_AAGUID)) {
                throw new SsoException("webauthn.attestation.certificate");
            }
            byte[] value = Der.unwrapOctetString(Der.unwrapOctetString(ext));
            if (!Bytes.constantTimeEquals(value, aaguid)) {
                throw new SsoException("webauthn.attestation.aaguid_mismatch");
            }
        }
    }

    private static Map<String, String> subject(X509Certificate cert) {
        Map<String, String> out = new java.util.HashMap<>();
        Der.Tlv name = Der.readSingle(cert.getSubjectX500Principal().getEncoded());
        for (Der.Tlv set : name.children()) {
            for (Der.Tlv atv : set.children()) {
                List<Der.Tlv> parts = atv.children();
                if (parts.size() != 2 || !parts.get(0).is(Der.CLASS_UNIVERSAL, 6)) {
                    throw new SsoException("webauthn.attestation.certificate");
                }
                String oid = Der.oid(parts.get(0).value());
                if (out.containsKey(oid)) {
                    throw new SsoException("webauthn.attestation.certificate");
                }
                out.put(oid, Bytes.utf8(parts.get(1).value()));
            }
        }
        return out;
    }

    private static AttestationResult u2f(Map<Object, Object> attStmt, AuthenticatorData authData,
                                         AttestedCredentialData acd, byte[] cdh) {
        allowedKeys(attStmt, Set.of("sig", "x5c"));
        byte[] sig = sig(attStmt);
        List<X509Certificate> chain = x5c(attStmt);
        if (chain.size() != 1 || !CoseKey.isP256(chain.get(0).getPublicKey())) {
            throw new SsoException("webauthn.attestation.certificate");
        }
        CoseKey key = acd.credentialPublicKey();
        if (key.algorithm() != CoseAlgorithm.ES256) {
            throw new SsoException("webauthn.attestation.alg_mismatch");
        }
        ECPublicKey ec = (ECPublicKey) key.publicKey();
        byte[] pk = Bytes.concat(new byte[]{0x04}, CoseKey.fixed(ec.getW().getAffineX(), 32), CoseKey.fixed(ec.getW().getAffineY(), 32));
        byte[] data = Bytes.concat(new byte[]{0x00}, authData.rpIdHash(), cdh, acd.credentialId(), pk);
        if (!CoseAlgorithm.ES256.verify(chain.get(0).getPublicKey(), data, sig)) {
            throw new SsoException("webauthn.attestation.signature");
        }
        return new AttestationResult("fido-u2f", AttestationType.BASIC, chain, false);
    }

    private static AttestationResult apple(Map<Object, Object> attStmt, AuthenticatorData authData,
                                           AttestedCredentialData acd, byte[] cdh) {
        allowedKeys(attStmt, Set.of("x5c", "alg"));
        List<X509Certificate> chain = x5c(attStmt);
        X509Certificate leaf = chain.get(0);
        byte[] nonce = Hashes.sha256(signed(authData, cdh));
        byte[] ext = leaf.getExtensionValue(OID_APPLE_NONCE);
        if (ext == null) {
            throw new SsoException("webauthn.attestation.apple_nonce");
        }
        byte[] found = null;
        try {
            Der.Tlv seq = Der.readSingle(Der.unwrapOctetString(ext));
            for (Der.Tlv t : seq.children()) {
                if (t.is(Der.CLASS_CONTEXT, 1)) {
                    Der.Tlv inner = Der.readSingle(t.value());
                    if (inner.is(Der.CLASS_UNIVERSAL, 4)) {
                        found = inner.value();
                    }
                }
            }
        } catch (SsoException e) {
            throw new SsoException("webauthn.attestation.apple_nonce");
        }
        if (found == null || !Bytes.constantTimeEquals(found, nonce)) {
            throw new SsoException("webauthn.attestation.apple_nonce");
        }
        if (!acd.credentialPublicKey().sameKey(leaf.getPublicKey())) {
            throw new SsoException("webauthn.attestation.key_mismatch");
        }
        return new AttestationResult("apple", AttestationType.ANONCA, chain, false);
    }

    private static AttestationResult androidKey(Map<Object, Object> attStmt, AuthenticatorData authData,
                                                AttestedCredentialData acd, byte[] cdh) {
        allowedKeys(attStmt, Set.of("alg", "sig", "x5c"));
        CoseAlgorithm alg = alg(attStmt);
        byte[] sig = sig(attStmt);
        List<X509Certificate> chain = x5c(attStmt);
        X509Certificate leaf = chain.get(0);
        if (!CoseKey.keyMatches(alg, leaf.getPublicKey())) {
            throw new SsoException("webauthn.attestation.alg_mismatch");
        }
        if (!alg.verify(leaf.getPublicKey(), signed(authData, cdh), sig)) {
            throw new SsoException("webauthn.attestation.signature");
        }
        if (!acd.credentialPublicKey().sameKey(leaf.getPublicKey())) {
            throw new SsoException("webauthn.attestation.key_mismatch");
        }
        byte[] ext = leaf.getExtensionValue(OID_ANDROID_KEY);
        if (ext == null) {
            throw new SsoException("webauthn.attestation.android_extension");
        }
        try {
            List<Der.Tlv> desc = Der.readSingle(Der.unwrapOctetString(ext)).children();
            if (desc.size() < 8 || !desc.get(4).is(Der.CLASS_UNIVERSAL, 4)) {
                throw new SsoException("webauthn.attestation.android_extension");
            }
            if (!Bytes.constantTimeEquals(desc.get(4).value(), cdh)) {
                throw new SsoException("webauthn.attestation.android_challenge");
            }
            boolean originSeen = false;
            boolean purposeSeen = false;
            for (int i = 6; i <= 7; i++) {
                for (Der.Tlv auth : desc.get(i).children()) {
                    if (auth.is(Der.CLASS_CONTEXT, ANDROID_ALL_APPLICATIONS)) {
                        throw new SsoException("webauthn.attestation.android_all_applications");
                    }
                    if (auth.is(Der.CLASS_CONTEXT, ANDROID_ORIGIN)) {
                        if (Der.integer(Der.readSingle(auth.value())) != 0) {
                            throw new SsoException("webauthn.attestation.android_origin");
                        }
                        originSeen = true;
                    }
                    if (auth.is(Der.CLASS_CONTEXT, ANDROID_PURPOSE)) {
                        purposeSeen = true;
                        boolean sign = false;
                        for (Der.Tlv p : Der.readSingle(auth.value()).children()) {
                            sign |= Der.integer(p) == 2;
                        }
                        if (!sign) {
                            throw new SsoException("webauthn.attestation.android_purpose");
                        }
                    }
                }
            }
            if (!originSeen) {
                throw new SsoException("webauthn.attestation.android_origin");
            }
            if (!purposeSeen) {
                throw new SsoException("webauthn.attestation.android_purpose");
            }
        } catch (SsoException e) {
            if (e.code().startsWith("webauthn.attestation.")) {
                throw e;
            }
            throw new SsoException("webauthn.attestation.android_extension");
        }
        return new AttestationResult("android-key", AttestationType.BASIC, chain, false);
    }

    private static AttestationResult tpm(Map<Object, Object> attStmt) {
        if (!"2.0".equals(attStmt.get("ver")) || !(attStmt.get("alg") instanceof Long)
                || !(attStmt.get("sig") instanceof byte[]) || !(attStmt.get("certInfo") instanceof byte[])
                || !(attStmt.get("pubArea") instanceof byte[])) {
            throw new SsoException("webauthn.attestation.invalid");
        }
        x5c(attStmt);
        return new AttestationResult("tpm", AttestationType.UNVERIFIED, List.of(), false);
    }

    private static AttestationResult safetynet(Map<Object, Object> attStmt) {
        if (!(attStmt.get("ver") instanceof String) || !(attStmt.get("response") instanceof byte[])) {
            throw new SsoException("webauthn.attestation.invalid");
        }
        return new AttestationResult("android-safetynet", AttestationType.UNVERIFIED, List.of(), false);
    }
}
