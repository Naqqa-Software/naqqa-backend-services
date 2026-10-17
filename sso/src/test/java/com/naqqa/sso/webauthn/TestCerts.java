package com.naqqa.sso.webauthn;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

final class TestCerts {
    static final ZonedDateTime NOT_BEFORE = ZonedDateTime.of(2025, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    static final ZonedDateTime NOT_AFTER = ZonedDateTime.of(2035, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final AtomicLong SERIAL = new AtomicLong(1000);

    record Ext(String oid, boolean critical, byte[] value) {
    }

    record Issued(X509Certificate cert, KeyPair keys) {
    }

    private TestCerts() {
    }

    static KeyPair ecKeys() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] name(String c, String o, String ou, String cn) {
        List<byte[]> rdns = new ArrayList<>();
        if (c != null) {
            rdns.add(TestDer.set(TestDer.seq(TestDer.oid("2.5.4.6"), TestDer.printable(c))));
        }
        if (o != null) {
            rdns.add(TestDer.set(TestDer.seq(TestDer.oid("2.5.4.10"), TestDer.utf8(o))));
        }
        if (ou != null) {
            rdns.add(TestDer.set(TestDer.seq(TestDer.oid("2.5.4.11"), TestDer.utf8(ou))));
        }
        if (cn != null) {
            rdns.add(TestDer.set(TestDer.seq(TestDer.oid("2.5.4.3"), TestDer.utf8(cn))));
        }
        return TestDer.seq(rdns.toArray(new byte[0][]));
    }

    static byte[] packedSubject() {
        return name("RO", "Naqqa Test", "Authenticator Attestation", "Test Authenticator");
    }

    static X509Certificate build(byte[] subject, PublicKey subjectKey, byte[] issuer, PrivateKey issuerKey,
                                 boolean ca, List<Ext> extensions) {
        try {
            String sigOid = issuerKey instanceof ECPrivateKey ? "1.2.840.10045.4.3.2" : "1.2.840.113549.1.1.11";
            byte[] algId = issuerKey instanceof ECPrivateKey ? TestDer.seq(TestDer.oid(sigOid))
                    : TestDer.seq(TestDer.oid(sigOid), TestDer.nul());
            List<byte[]> exts = new ArrayList<>();
            exts.add(TestDer.seq(TestDer.oid("2.5.29.19"), TestDer.bool(true),
                    TestDer.octet(ca ? TestDer.seq(TestDer.bool(true)) : TestDer.seq())));
            if (ca) {
                exts.add(TestDer.seq(TestDer.oid("2.5.29.15"), TestDer.bool(true),
                        TestDer.octet(TestDer.tlv(0x03, new byte[]{0x01, 0x06}))));
            }
            for (Ext e : extensions) {
                exts.add(e.critical() ? TestDer.seq(TestDer.oid(e.oid()), TestDer.bool(true), TestDer.octet(e.value()))
                        : TestDer.seq(TestDer.oid(e.oid()), TestDer.octet(e.value())));
            }
            byte[] tbs = TestDer.seq(
                    TestDer.explicit(0, TestDer.integer(2)),
                    TestDer.tlv(0x02, BigInteger.valueOf(SERIAL.incrementAndGet()).toByteArray()),
                    algId,
                    issuer,
                    TestDer.seq(TestDer.utcTime(NOT_BEFORE), TestDer.utcTime(NOT_AFTER)),
                    subject,
                    subjectKey.getEncoded(),
                    TestDer.explicit(3, TestDer.seq(exts.toArray(new byte[0][]))));
            Signature s = Signature.getInstance(issuerKey instanceof ECPrivateKey ? "SHA256withECDSA" : "SHA256withRSA");
            s.initSign(issuerKey);
            s.update(tbs);
            byte[] cert = TestDer.seq(tbs, algId, TestDer.bitString(s.sign()));
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(cert));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static Issued root() {
        KeyPair kp = ecKeys();
        byte[] n = name("RO", "Naqqa Test", null, "Naqqa Test Root");
        return new Issued(build(n, kp.getPublic(), n, kp.getPrivate(), true, List.of()), kp);
    }

    static Issued leaf(Issued issuer, byte[] subject, KeyPair subjectKeys, List<Ext> exts) {
        byte[] issuerName = issuer.cert().getSubjectX500Principal().getEncoded();
        return new Issued(build(subject, subjectKeys.getPublic(), issuerName, issuer.keys().getPrivate(), false, exts), subjectKeys);
    }

    static byte[] encoded(X509Certificate c) {
        try {
            return c.getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static Ext aaguidExt(byte[] aaguid) {
        return new Ext(AttestationVerifier.OID_FIDO_AAGUID, false, TestDer.octet(aaguid));
    }

    static Ext appleNonceExt(byte[] nonce) {
        return new Ext(AttestationVerifier.OID_APPLE_NONCE, false, TestDer.seq(TestDer.explicit(1, TestDer.octet(nonce))));
    }

    static Ext androidKeyExt(byte[] challenge, boolean allApplications) {
        return androidKeyExt(challenge, allApplications, true, true);
    }

    static Ext androidKeyExt(byte[] challenge, boolean allApplications, boolean withPurpose, boolean withOrigin) {
        byte[] purpose = withPurpose ? TestDer.tlv(0xA1, TestDer.set(TestDer.integer(2))) : new byte[0];
        byte[] origin = withOrigin ? TestDer.tlv(0xBF853E, TestDer.integer(0)) : new byte[0];
        byte[] tee = allApplications
                ? TestDer.seq(purpose, TestDer.tlv(0xBF8458, TestDer.nul()), origin)
                : TestDer.seq(purpose, origin);
        byte[] desc = TestDer.seq(TestDer.integer(3), TestDer.enumerated(1), TestDer.integer(4), TestDer.enumerated(1),
                TestDer.octet(challenge), TestDer.octet(new byte[0]), TestDer.seq(), tee);
        return new Ext(AttestationVerifier.OID_ANDROID_KEY, false, desc);
    }
}
