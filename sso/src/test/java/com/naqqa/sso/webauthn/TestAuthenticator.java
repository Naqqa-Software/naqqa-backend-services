package com.naqqa.sso.webauthn;

import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;

import java.io.ByteArrayOutputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.util.LinkedHashMap;
import java.util.Map;

final class TestAuthenticator {
    static final int UP = AuthenticatorData.FLAG_UP;
    static final int UV = AuthenticatorData.FLAG_UV;
    static final int BE = AuthenticatorData.FLAG_BE;
    static final int BS = AuthenticatorData.FLAG_BS;
    static final int AT = AuthenticatorData.FLAG_AT;
    static final int ED = AuthenticatorData.FLAG_ED;

    final CoseAlgorithm alg;
    final KeyPair keys;
    final byte[] credentialId;
    byte[] aaguid = new byte[16];
    long counter;

    TestAuthenticator(CoseAlgorithm alg, KeyPair keys, byte[] credentialId) {
        this.alg = alg;
        this.keys = keys;
        this.credentialId = credentialId;
    }

    static TestAuthenticator create(CoseAlgorithm alg) {
        return new TestAuthenticator(alg, generate(alg), Bytes.random(32));
    }

    static KeyPair generate(CoseAlgorithm alg) {
        try {
            return switch (alg) {
                case ES256 -> {
                    KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
                    g.initialize(new ECGenParameterSpec("secp256r1"));
                    yield g.generateKeyPair();
                }
                case EDDSA -> KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
                case RS256, PS256 -> {
                    KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
                    g.initialize(2048);
                    yield g.generateKeyPair();
                }
            };
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    CoseKey coseKey() {
        return CoseKey.of(keys.getPublic(), alg);
    }

    byte[] sign(byte[] data) {
        return sign(alg, keys.getPrivate(), data);
    }

    static byte[] sign(CoseAlgorithm alg, PrivateKey key, byte[] data) {
        try {
            Signature s = switch (alg) {
                case ES256 -> Signature.getInstance("SHA256withECDSA");
                case EDDSA -> Signature.getInstance("Ed25519");
                case RS256 -> Signature.getInstance("SHA256withRSA");
                case PS256 -> {
                    Signature p = Signature.getInstance("RSASSA-PSS");
                    p.setParameter(new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1));
                    yield p;
                }
            };
            s.initSign(key);
            s.update(data);
            return s.sign();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    byte[] attestedCredentialData() {
        return attestedCredentialData(aaguid, credentialId, coseKey().encoded());
    }

    static byte[] attestedCredentialData(byte[] aaguid, byte[] credId, byte[] cose) {
        return Bytes.concat(aaguid, new byte[]{(byte) (credId.length >>> 8), (byte) credId.length}, credId, cose);
    }

    static byte[] authData(String rpId, int flags, long count, byte[] attested, byte[] ext) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(Hashes.sha256(Bytes.utf8(rpId)));
        out.write(flags);
        out.write((int) (count >>> 24));
        out.write((int) (count >>> 16));
        out.write((int) (count >>> 8));
        out.write((int) count);
        if (attested != null) {
            out.writeBytes(attested);
        }
        if (ext != null) {
            out.writeBytes(ext);
        }
        return out.toByteArray();
    }

    static byte[] clientData(String type, byte[] challenge, String origin, Boolean crossOrigin) {
        String json = "{\"type\":\"" + type + "\",\"challenge\":\"" + Bytes.b64url(challenge) + "\",\"origin\":\"" + origin + "\""
                + (crossOrigin == null ? "" : ",\"crossOrigin\":" + crossOrigin) + "}";
        return Bytes.utf8(json);
    }

    static byte[] attestationObject(String fmt, Map<Object, Object> attStmt, byte[] authData) {
        Map<Object, Object> m = new LinkedHashMap<>();
        m.put("fmt", fmt);
        m.put("attStmt", attStmt);
        m.put("authData", authData);
        return Cbor.encode(m);
    }
}
