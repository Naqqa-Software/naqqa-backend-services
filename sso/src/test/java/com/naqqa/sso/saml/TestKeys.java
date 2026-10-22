package com.naqqa.sso.saml;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;

public final class TestKeys {
    public record Cred(PrivateKey key, X509Certificate cert) {
    }

    public static final Cred IDP = rsa("idp", 2048);
    public static final Cred SP = rsa("sp", 2048);
    public static final Cred SP_ENC = rsa("sp-enc", 2048);
    public static final Cred ATTACKER = rsa("attacker", 2048);
    public static final Cred IDP_EC = ec("idp-ec");

    private TestKeys() {
    }

    public static Cred rsa(String cn, int bits) {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(bits);
            return selfSigned(g.generateKeyPair(), cn, "SHA256withRSA",
                    new byte[]{0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x0b}, true);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static Cred ec(String cn) {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            return selfSigned(g.generateKeyPair(), cn, "SHA256withECDSA",
                    new byte[]{0x2a, (byte) 0x86, 0x48, (byte) 0xce, 0x3d, 0x04, 0x03, 0x02}, false);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Cred selfSigned(KeyPair kp, String cn, String jca, byte[] sigOid, boolean nullParams) throws Exception {
        byte[] algId = nullParams ? seq(tlv(0x06, sigOid), new byte[]{0x05, 0x00}) : seq(tlv(0x06, sigOid));
        byte[] name = seq(tlv(0x31, seq(tlv(0x06, new byte[]{0x55, 0x04, 0x03}),
                tlv(0x0c, cn.getBytes(StandardCharsets.UTF_8)))));
        byte[] validity = seq(tlv(0x17, "200101000000Z".getBytes(StandardCharsets.US_ASCII)),
                tlv(0x17, "491231235959Z".getBytes(StandardCharsets.US_ASCII)));
        byte[] tbs = seq(tlv(0xa0, tlv(0x02, new byte[]{2})),
                tlv(0x02, BigInteger.valueOf(System.nanoTime() & 0x7fffffffL).toByteArray()),
                algId, name, validity, name, kp.getPublic().getEncoded());
        Signature s = Signature.getInstance(jca);
        s.initSign(kp.getPrivate());
        s.update(tbs);
        byte[] sig = s.sign();
        byte[] bits = new byte[sig.length + 1];
        System.arraycopy(sig, 0, bits, 1, sig.length);
        byte[] der = seq(tbs, algId, tlv(0x03, bits));
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
        return new Cred(kp.getPrivate(), cert);
    }

    private static byte[] seq(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return tlv(0x30, out.toByteArray());
    }

    private static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int len = value.length;
        if (len < 128) {
            out.write(len);
        } else if (len < 256) {
            out.write(0x81);
            out.write(len);
        } else {
            out.write(0x82);
            out.write(len >> 8);
            out.write(len & 0xff);
        }
        out.writeBytes(value);
        return out.toByteArray();
    }
}
