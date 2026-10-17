package com.naqqa.auth.sso.saml;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

public final class SelfSignedCertificate {

    private static final byte[] SHA256_WITH_RSA = {0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x0b};

    private SelfSignedCertificate() {
    }

    public static X509Certificate rsa(String commonName, PublicKey publicKey, PrivateKey privateKey) {
        try {
            byte[] algId = seq(tlv(0x06, SHA256_WITH_RSA), new byte[]{0x05, 0x00});
            byte[] name = seq(tlv(0x31, seq(tlv(0x06, new byte[]{0x55, 0x04, 0x03}),
                    tlv(0x0c, commonName.getBytes(StandardCharsets.UTF_8)))));
            byte[] validity = seq(tlv(0x17, "200101000000Z".getBytes(StandardCharsets.US_ASCII)),
                    tlv(0x17, "491231235959Z".getBytes(StandardCharsets.US_ASCII)));
            byte[] serial = new BigInteger(63, new SecureRandom()).add(BigInteger.ONE).toByteArray();
            byte[] tbs = seq(tlv(0xa0, tlv(0x02, new byte[]{2})), tlv(0x02, serial), algId, name, validity, name,
                    publicKey.getEncoded());
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initSign(privateKey);
            s.update(tbs);
            byte[] sig = s.sign();
            byte[] bits = new byte[sig.length + 1];
            System.arraycopy(sig, 0, bits, 1, sig.length);
            byte[] der = seq(tbs, algId, tlv(0x03, bits));
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot create SAML signing certificate", e);
        }
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
