package com.naqqa.sso.jose;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.crypto.Jwk;
import com.naqqa.sso.crypto.KeyRing;
import com.naqqa.sso.crypto.MutableClock;
import com.naqqa.sso.crypto.SigningAlgorithm;
import com.naqqa.sso.crypto.SigningKey;
import com.naqqa.sso.json.Json;
import com.naqqa.sso.util.Bytes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.KeyAgreement;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwksJweTest {
    private static final byte[] PT = "secret payload".getBytes(StandardCharsets.UTF_8);

    private static String code(Runnable r) {
        return assertThrows(SsoException.class, r::run).code();
    }

    private static KeyPair p256() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }

    @Test
    @DisplayName("SSO-T-jose-038 JWKS publish contains only public members of unexpired keys")
    void publish() {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        KeyRing ring = KeyRing.of(clock, SigningKey.generate(SigningAlgorithm.RS256));
        ring.rotate(SigningKey.generate(SigningAlgorithm.EdDSA), Duration.ofMinutes(5));
        String json = Jwks.publish(ring);
        for (String m : new String[]{"\"d\"", "\"p\"", "\"q\"", "\"dp\"", "\"dq\"", "\"qi\""}) {
            assertFalse(json.contains(m));
        }
        List<SigningKey> keys = Jwks.parse(json);
        assertEquals(2, keys.size());
        clock.advance(Duration.ofMinutes(5));
        assertEquals(1, Jwks.parse(Jwks.publish(ring)).size());
    }

    @Test
    @DisplayName("SSO-T-jose-039 JWKS round-trip verifies tokens signed by the source ring")
    void roundTrip() {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        KeyRing ring = KeyRing.of(clock, SigningKey.generate(SigningAlgorithm.ES256));
        String token = Jws.sign(ring.active(), PT);
        KeyRing remote = Jwks.toKeyRing(Jwks.publish(ring), clock);
        assertEquals("secret payload", Jws.verify(token, remote).payloadUtf8());
    }

    @Test
    @DisplayName("SSO-T-jose-040 RFC 7517 A.1 key set: enc key skipped, RS256 key parsed with its kid")
    void rfc7517() {
        List<SigningKey> keys = Jwks.parse(RfcVectors.RFC7517_A1_JWKS);
        assertEquals(1, keys.size());
        assertEquals("2011-04-29", keys.get(0).kid());
        assertEquals(SigningAlgorithm.RS256, keys.get(0).alg());
    }

    @Test
    @DisplayName("SSO-T-jose-041 JWKS containing private key material is rejected")
    void privateMaterial() {
        SigningKey k = SigningKey.generate(SigningAlgorithm.ES256);
        String json = Json.write(Map.of("keys", List.of(Jwk.toPrivateJwk(k))));
        assertEquals("jwks.private_material", code(() -> Jwks.parse(json)));
        String sym = "{\"keys\":[{\"kty\":\"oct\",\"k\":\"c2VjcmV0\"}]}";
        assertEquals("jwks.private_material", code(() -> Jwks.parse(sym)));
    }

    @Test
    @DisplayName("SSO-T-jose-042 JWKS duplicate kid and malformed sets are rejected; unsupported algs skipped")
    void jwksShape() {
        SigningKey k = SigningKey.generate(SigningAlgorithm.EdDSA);
        Map<String, Object> pub = Jwk.toPublicJwk(k);
        assertEquals("jwks.duplicate_kid", code(() -> Jwks.parse(Json.write(Map.of("keys", List.of(pub, pub))))));
        assertEquals("jwks.invalid", code(() -> Jwks.parse("{\"keys\":{}}")));
        assertEquals("jwks.invalid", code(() -> Jwks.parse("[]")));
        Map<String, Object> rs384 = new LinkedHashMap<>(Jwk.toPublicJwk(SigningKey.generate(SigningAlgorithm.RS256)));
        rs384.put("alg", "RS384");
        assertTrue(Jwks.parse(Json.write(Map.of("keys", List.of(rs384)))).isEmpty());
    }

    @Test
    @DisplayName("SSO-T-jose-043 RFC 7518 Appendix C ECDH-ES Concat KDF vector")
    void rfc7518C() throws Exception {
        SigningKey alice = Jwk.parse(RfcVectors.RFC7518_C_ALICE);
        SigningKey bob = Jwk.parse(RfcVectors.RFC7518_C_BOB);
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(alice.privateKey());
        ka.doPhase(bob.publicKey(), true);
        byte[] z = ka.generateSecret();
        byte[] expectedZ = new byte[]{(byte) 158, 86, (byte) 217, 29, (byte) 129, 113, 53, (byte) 211, 114, (byte) 131, 66, (byte) 131,
                (byte) 191, (byte) 132, 38, (byte) 156, (byte) 251, 49, 110, (byte) 163, (byte) 218, (byte) 128, 106, 72, (byte) 246,
                (byte) 218, (byte) 167, 121, (byte) 140, (byte) 254, (byte) 144, (byte) 196};
        assertArrayEquals(expectedZ, z);
        byte[] cek = ConcatKdf.derive(z, 128, "A128GCM", "Alice".getBytes(StandardCharsets.UTF_8), "Bob".getBytes(StandardCharsets.UTF_8));
        assertEquals(RfcVectors.RFC7518_C_DERIVED, Bytes.b64url(cek));
    }

    @Test
    @DisplayName("SSO-T-jose-044 JWE dir + A256GCM round-trip and header shape")
    void dirRoundTrip() {
        byte[] key = Bytes.random(32);
        String jwe = Jwe.encryptDir(key, PT, Map.of("kid", "k1", "cty", "JWT"));
        assertEquals(5, jwe.split("\\.", -1).length);
        assertTrue(jwe.contains(".."));
        Jwe.Decrypted d = Jwe.decryptDir(jwe, key);
        assertArrayEquals(PT, d.plaintext());
        assertEquals("dir", d.header().get("alg"));
        assertEquals("A256GCM", d.header().get("enc"));
        assertEquals("jwe.key_length", code(() -> Jwe.encryptDir(new byte[16], PT, null)));
    }

    @Test
    @DisplayName("SSO-T-jose-045 JWE ECDH-ES P-256 + A256GCM round-trip with apu/apv")
    void ecdhRoundTrip() throws Exception {
        KeyPair bob = p256();
        String jwe = Jwe.encryptEcdhEs((ECPublicKey) bob.getPublic(), PT, "Alice".getBytes(StandardCharsets.UTF_8), "Bob".getBytes(StandardCharsets.UTF_8), null);
        Jwe.Decrypted d = Jwe.decryptEcdhEs(jwe, (ECPrivateKey) bob.getPrivate());
        assertEquals("secret payload", d.plaintextUtf8());
        assertEquals("QWxpY2U", d.header().get("apu"));
        KeyPair eve = p256();
        assertEquals("jwe.decrypt_failed", code(() -> Jwe.decryptEcdhEs(jwe, (ECPrivateKey) eve.getPrivate())));
    }

    @Test
    @DisplayName("SSO-T-jose-046 JWE tampering of header, iv, ciphertext or tag fails")
    void jweTamper() {
        byte[] key = Bytes.random(32);
        String jwe = Jwe.encryptDir(key, PT, null);
        String[] p = jwe.split("\\.", -1);
        for (int i : new int[]{2, 3, 4}) {
            String[] q = p.clone();
            byte[] raw = Bytes.b64urlDecode(q[i]);
            raw[0] ^= 1;
            q[i] = Bytes.b64url(raw);
            String t = String.join(".", q);
            assertEquals("jwe.decrypt_failed", code(() -> Jwe.decryptDir(t, key)));
        }
        String hdr = Bytes.b64url("{\"alg\":\"dir\",\"enc\":\"A256GCM\",\"kid\":\"x\"}".getBytes(StandardCharsets.UTF_8));
        String swapped = hdr + "." + p[1] + "." + p[2] + "." + p[3] + "." + p[4];
        assertEquals("jwe.decrypt_failed", code(() -> Jwe.decryptDir(swapped, key)));
        assertEquals("jwe.decrypt_failed", code(() -> Jwe.decryptDir(jwe, Bytes.random(32))));
    }

    @Test
    @DisplayName("SSO-T-jose-047 JWE rejects other enc, alg confusion, zip, crit and non-empty encrypted key")
    void jweHeaderPolicy() throws Exception {
        byte[] key = Bytes.random(32);
        String jwe = Jwe.encryptDir(key, PT, null);
        String[] p = jwe.split("\\.", -1);
        String a128 = Bytes.b64url("{\"alg\":\"dir\",\"enc\":\"A128GCM\"}".getBytes(StandardCharsets.UTF_8));
        assertEquals("jwe.enc_unsupported", code(() -> Jwe.decryptDir(a128 + "." + p[1] + "." + p[2] + "." + p[3] + "." + p[4], key)));
        String zip = Bytes.b64url("{\"alg\":\"dir\",\"enc\":\"A256GCM\",\"zip\":\"DEF\"}".getBytes(StandardCharsets.UTF_8));
        assertEquals("jwe.header_invalid", code(() -> Jwe.decryptDir(zip + "." + p[1] + "." + p[2] + "." + p[3] + "." + p[4], key)));
        String crit = Bytes.b64url("{\"alg\":\"dir\",\"enc\":\"A256GCM\",\"crit\":[\"x\"],\"x\":1}".getBytes(StandardCharsets.UTF_8));
        assertEquals("jwe.header_invalid", code(() -> Jwe.decryptDir(crit + "." + p[1] + "." + p[2] + "." + p[3] + "." + p[4], key)));
        assertEquals("jwe.encrypted_key_unexpected", code(() -> Jwe.decryptDir(p[0] + ".AAAA." + p[2] + "." + p[3] + "." + p[4], key)));
        KeyPair kp = p256();
        assertEquals("jwe.alg_mismatch", code(() -> Jwe.decryptEcdhEs(jwe, (ECPrivateKey) kp.getPrivate())));
        String ecdh = Jwe.encryptEcdhEs((ECPublicKey) kp.getPublic(), PT, null, null, null);
        assertEquals("jwe.alg_mismatch", code(() -> Jwe.decryptDir(ecdh, key)));
        assertEquals("jwe.header_forbidden", code(() -> Jwe.encryptDir(key, PT, Map.of("zip", "DEF"))));
    }

    @Test
    @DisplayName("SSO-T-jose-048 JWE ECDH-ES ephemeral key off the curve is rejected (invalid curve attack)")
    void invalidCurve() throws Exception {
        KeyPair bob = p256();
        String jwe = Jwe.encryptEcdhEs((ECPublicKey) bob.getPublic(), PT, null, null, null);
        String[] p = jwe.split("\\.", -1);
        Map<String, Object> header = Json.parseObject(Bytes.b64urlDecode(p[0]));
        Map<String, Object> epk = new LinkedHashMap<>(Json.asObject(header.get("epk")));
        byte[] y = Bytes.b64urlDecode((String) epk.get("y"));
        y[31] ^= 1;
        epk.put("y", Bytes.b64url(y));
        header.put("epk", epk);
        String forged = Bytes.b64url(Json.writeUtf8(header)) + "." + p[1] + "." + p[2] + "." + p[3] + "." + p[4];
        assertEquals("jwe.epk_invalid", code(() -> Jwe.decryptEcdhEs(forged, (ECPrivateKey) bob.getPrivate())));
        header.put("epk", Map.of("kty", "EC", "crv", "P-384", "x", "AA", "y", "AA"));
        String wrongCurve = Bytes.b64url(Json.writeUtf8(header)) + "." + p[1] + "." + p[2] + "." + p[3] + "." + p[4];
        assertEquals("jwe.epk_invalid", code(() -> Jwe.decryptEcdhEs(wrongCurve, (ECPrivateKey) bob.getPrivate())));
    }

    @Test
    @DisplayName("SSO-T-jose-049 JWE malformed shapes and oversize are rejected")
    void jweShape() {
        byte[] key = Bytes.random(32);
        assertEquals("jwe.malformed", code(() -> Jwe.decryptDir("a.b.c", key)));
        assertEquals("jwe.too_large", code(() -> Jwe.decryptDir("a".repeat(Jwe.MAX_TOKEN_SIZE + 1), key)));
        String jwe = Jwe.encryptDir(key, PT, null);
        String[] p = jwe.split("\\.", -1);
        String shortIv = p[0] + ".." + Bytes.b64url(new byte[8]) + "." + p[3] + "." + p[4];
        assertEquals("jwe.malformed", code(() -> Jwe.decryptDir(shortIv, key)));
        String padded = p[0] + ".." + p[2] + "." + p[3] + "." + p[4] + "=";
        assertEquals("jwe.bad_base64", code(() -> Jwe.decryptDir(padded, key)));
    }
}
