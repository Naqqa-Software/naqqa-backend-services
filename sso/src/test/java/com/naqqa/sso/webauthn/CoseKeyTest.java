package com.naqqa.sso.webauthn;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.util.Bytes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoseKeyTest {
    private static String code(Map<Object, Object> m) {
        return assertThrows(SsoException.class, () -> CoseKey.decode(Cbor.encode(m))).code();
    }

    private static Map<Object, Object> ec(ECPublicKey k) {
        Map<Object, Object> m = new LinkedHashMap<>();
        m.put(1L, 2L);
        m.put(3L, -7L);
        m.put(-1L, 1L);
        m.put(-2L, CoseKey.fixed(k.getW().getAffineX(), 32));
        m.put(-3L, CoseKey.fixed(k.getW().getAffineY(), 32));
        return m;
    }

    private static Map<Object, Object> ecMap() {
        return ec((ECPublicKey) TestAuthenticator.generate(CoseAlgorithm.ES256).getPublic());
    }

    @ParameterizedTest
    @EnumSource(CoseAlgorithm.class)
    @DisplayName("SSO-T-webauthn-030 COSE round trip and signature verification for every algorithm")
    void roundTrip(CoseAlgorithm alg) {
        TestAuthenticator a = TestAuthenticator.create(alg);
        CoseKey k = CoseKey.decode(a.coseKey().encoded());
        assertEquals(alg, k.algorithm());
        assertTrue(k.sameKey(a.keys.getPublic()));
        byte[] msg = Bytes.utf8("naqqa");
        assertTrue(k.verify(msg, a.sign(msg)));
        assertFalse(k.verify(Bytes.utf8("naqqb"), a.sign(msg)));
    }

    @Test
    @DisplayName("SSO-T-webauthn-031 EC point not on curve rejected")
    void pointNotOnCurve() {
        Map<Object, Object> m = ecMap();
        byte[] y = ((byte[]) m.get(-3L)).clone();
        y[31] ^= 1;
        m.put(-3L, y);
        assertEquals("webauthn.cose.point_invalid", code(m));
    }

    @Test
    @DisplayName("SSO-T-webauthn-032 EC coordinates at or above field prime rejected")
    void coordinateTooLarge() {
        Map<Object, Object> m = ecMap();
        byte[] ff = new byte[32];
        java.util.Arrays.fill(ff, (byte) 0xFF);
        m.put(-2L, ff);
        assertEquals("webauthn.cose.point_invalid", code(m));
    }

    @Test
    @DisplayName("SSO-T-webauthn-033 EC zero point rejected")
    void zeroPoint() {
        Map<Object, Object> m = ecMap();
        m.put(-2L, new byte[32]);
        m.put(-3L, new byte[32]);
        assertEquals("webauthn.cose.point_invalid", code(m));
    }

    @Test
    @DisplayName("SSO-T-webauthn-034 alg and kty mismatch rejected")
    void algKtyMismatch() {
        Map<Object, Object> m = ecMap();
        m.put(3L, -257L);
        assertEquals("webauthn.cose.kty_mismatch", code(m));
        Map<Object, Object> m2 = ecMap();
        m2.put(1L, 1L);
        assertEquals("webauthn.cose.kty_mismatch", code(m2));
    }

    @Test
    @DisplayName("SSO-T-webauthn-035 unknown algorithm rejected")
    void unknownAlg() {
        Map<Object, Object> m = ecMap();
        m.put(3L, -35L);
        assertEquals("webauthn.cose.alg_unsupported", code(m));
        m.put(3L, 0L);
        assertEquals("webauthn.cose.alg_unsupported", code(m));
    }

    @Test
    @DisplayName("SSO-T-webauthn-036 unknown curve rejected for EC2 and OKP")
    void unknownCurve() {
        Map<Object, Object> m = ecMap();
        m.put(-1L, 2L);
        assertEquals("webauthn.cose.crv_unsupported", code(m));
        Map<Object, Object> okp = new LinkedHashMap<>();
        okp.put(1L, 1L);
        okp.put(3L, -8L);
        okp.put(-1L, 4L);
        okp.put(-2L, new byte[32]);
        assertEquals("webauthn.cose.crv_unsupported", code(okp));
    }

    @Test
    @DisplayName("SSO-T-webauthn-037 private key parameter in COSE key rejected")
    void privateKey() {
        Map<Object, Object> m = ecMap();
        m.put(-4L, new byte[32]);
        assertEquals("webauthn.cose.private_key", code(m));
    }

    @Test
    @DisplayName("SSO-T-webauthn-038 RSA modulus below 2048 bits rejected")
    void weakRsa() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(1024);
        RSAPublicKey k = (RSAPublicKey) g.generateKeyPair().getPublic();
        Map<Object, Object> m = new LinkedHashMap<>();
        m.put(1L, 3L);
        m.put(3L, -257L);
        m.put(-1L, CoseKey.fixed(k.getModulus(), 128));
        m.put(-2L, new byte[]{1, 0, 1});
        assertEquals("webauthn.cose.rsa_invalid", code(m));
    }

    @Test
    @DisplayName("SSO-T-webauthn-039 RSA even exponent and leading zero modulus rejected")
    void rsaBadParams() {
        RSAPublicKey k = (RSAPublicKey) TestAuthenticator.generate(CoseAlgorithm.RS256).getPublic();
        Map<Object, Object> m = new LinkedHashMap<>();
        m.put(1L, 3L);
        m.put(3L, -257L);
        m.put(-1L, CoseKey.fixed(k.getModulus(), 256));
        m.put(-2L, new byte[]{1, 0, 0});
        assertEquals("webauthn.cose.rsa_invalid", code(m));
        m.put(-2L, new byte[]{1, 0, 1});
        m.put(-1L, Bytes.concat(new byte[]{0}, CoseKey.fixed(k.getModulus(), 256)));
        assertEquals("webauthn.cose.invalid", code(m));
    }

    @Test
    @DisplayName("SSO-T-webauthn-040 wrong coordinate length and missing parameters rejected")
    void wrongLength() {
        Map<Object, Object> m = ecMap();
        m.put(-2L, new byte[31]);
        assertEquals("webauthn.cose.invalid", code(m));
        Map<Object, Object> m2 = ecMap();
        m2.remove(-3L);
        assertEquals("webauthn.cose.invalid", code(m2));
        Map<Object, Object> m3 = ecMap();
        m3.remove(3L);
        assertEquals("webauthn.cose.invalid", code(m3));
    }

    @Test
    @DisplayName("SSO-T-webauthn-041 unknown COSE labels rejected")
    void unknownLabel() {
        Map<Object, Object> m = ecMap();
        m.put(-99L, 1L);
        assertEquals("webauthn.cose.unknown_param", code(m));
    }

    @Test
    @DisplayName("SSO-T-webauthn-042 COSE key encoding is preserved byte for byte")
    void encodingPreserved() {
        TestAuthenticator a = TestAuthenticator.create(CoseAlgorithm.EDDSA);
        byte[] enc = a.coseKey().encoded();
        assertArrayEquals(enc, CoseKey.decode(enc).encoded());
    }

    @Test
    @DisplayName("SSO-T-webauthn-043 signature with wrong algorithm parameters fails")
    void psVsPkcs1() {
        TestAuthenticator a = TestAuthenticator.create(CoseAlgorithm.RS256);
        CoseKey ps = CoseKey.of(a.keys.getPublic(), CoseAlgorithm.PS256);
        byte[] msg = Bytes.utf8("x");
        assertFalse(ps.verify(msg, a.sign(msg)));
        assertFalse(ps.verify(msg, new byte[0]));
        assertTrue(new BigInteger(1, ((RSAPublicKey) a.keys.getPublic()).getModulus().toByteArray()).bitLength() >= 2048);
    }
}
