package com.naqqa.sso.crypto;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.jose.RfcVectors;
import com.naqqa.sso.json.Json;
import com.naqqa.sso.util.Bytes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SigningKeyTest {
    private static final byte[] MSG = "hello naqqa".getBytes(StandardCharsets.UTF_8);

    private static String code(Runnable r) {
        return assertThrows(SsoException.class, r::run).code();
    }

    @Test
    @DisplayName("SSO-T-crypto-001 RS256 generated key signs and verifies")
    void rs256() {
        SigningKey k = SigningKey.generate(SigningAlgorithm.RS256);
        byte[] sig = k.sign(MSG);
        assertEquals(256, sig.length);
        assertTrue(k.verify(MSG, sig));
        assertFalse(k.verify("other".getBytes(StandardCharsets.UTF_8), sig));
    }

    @Test
    @DisplayName("SSO-T-crypto-002 ES256 signature is raw r||s of 64 bytes and verifies")
    void es256() {
        SigningKey k = SigningKey.generate(SigningAlgorithm.ES256);
        byte[] sig = k.sign(MSG);
        assertEquals(64, sig.length);
        assertTrue(k.verify(MSG, sig));
        sig[10] ^= 1;
        assertFalse(k.verify(MSG, sig));
    }

    @Test
    @DisplayName("SSO-T-crypto-003 EdDSA Ed25519 signs and verifies")
    void eddsa() {
        SigningKey k = SigningKey.generate(SigningAlgorithm.EdDSA);
        byte[] sig = k.sign(MSG);
        assertEquals(64, sig.length);
        assertTrue(k.verify(MSG, sig));
    }

    @Test
    @DisplayName("SSO-T-crypto-004 RSA keys below 2048 bits are rejected")
    void rsaTooSmall() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(1024);
        KeyPair kp = g.generateKeyPair();
        assertEquals("crypto.rsa_size", code(() -> SigningKey.of(SigningAlgorithm.RS256, kp.getPublic(), kp.getPrivate())));
        assertEquals("crypto.rsa_size", code(() -> SigningKey.generateRsa(1024)));
    }

    @Test
    @DisplayName("SSO-T-crypto-005 EC keys on curves other than P-256 are rejected")
    void ecWrongCurve() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp384r1"));
        KeyPair kp = g.generateKeyPair();
        assertEquals("crypto.curve_unsupported", code(() -> SigningKey.of(SigningAlgorithm.ES256, kp.getPublic(), kp.getPrivate())));
    }

    @Test
    @DisplayName("SSO-T-crypto-006 Ed448 keys are rejected for EdDSA")
    void ed448() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("Ed448").generateKeyPair();
        assertEquals("crypto.curve_unsupported", code(() -> SigningKey.of(SigningAlgorithm.EdDSA, kp.getPublic(), kp.getPrivate())));
    }

    @Test
    @DisplayName("SSO-T-crypto-007 key type not matching algorithm is rejected")
    void typeMismatch() {
        SigningKey rsa = SigningKey.generate(SigningAlgorithm.RS256);
        assertEquals("crypto.key_type_mismatch", code(() -> SigningKey.of(SigningAlgorithm.ES256, rsa.publicKey(), null)));
        assertEquals("crypto.key_type_mismatch", code(() -> SigningKey.of(SigningAlgorithm.EdDSA, rsa.publicKey(), null)));
    }

    @Test
    @DisplayName("SSO-T-crypto-008 public and private keys from different pairs are rejected")
    void pairMismatch() {
        SigningKey a = SigningKey.generate(SigningAlgorithm.ES256);
        SigningKey b = SigningKey.generate(SigningAlgorithm.ES256);
        assertEquals("crypto.key_pair_mismatch", code(() -> SigningKey.of(SigningAlgorithm.ES256, a.publicKey(), b.privateKey())));
    }

    @Test
    @DisplayName("SSO-T-crypto-009 ECDSA DER to raw and back round-trips JDK signatures")
    void derRoundTrip() throws Exception {
        SigningKey k = SigningKey.generate(SigningAlgorithm.ES256);
        for (int i = 0; i < 50; i++) {
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initSign(k.privateKey());
            s.update(new byte[]{(byte) i});
            byte[] der = s.sign();
            byte[] raw = EcdsaSignatures.derToRaw(der);
            assertEquals(64, raw.length);
            assertArrayEquals(der, EcdsaSignatures.rawToDer(raw));
        }
    }

    @Test
    @DisplayName("SSO-T-crypto-010 ECDSA DER with non-minimal integer is rejected")
    void derNonMinimal() {
        byte[] r = new byte[32];
        r[31] = 1;
        byte[] der = Der.encode(Der.SEQUENCE, Der.encode(Der.INTEGER, new byte[]{0, 1}), Der.encode(Der.INTEGER, new byte[]{1}));
        assertEquals("crypto.ecdsa_der_invalid", code(() -> EcdsaSignatures.derToRaw(der)));
    }

    @Test
    @DisplayName("SSO-T-crypto-011 ECDSA DER with negative or oversized integer is rejected")
    void derNegative() {
        byte[] neg = Der.encode(Der.SEQUENCE, Der.encode(Der.INTEGER, new byte[]{(byte) 0x80, 1}), Der.encode(Der.INTEGER, new byte[]{1}));
        assertEquals("crypto.ecdsa_der_invalid", code(() -> EcdsaSignatures.derToRaw(neg)));
        byte[] big = new byte[34];
        big[1] = 0x7F;
        byte[] over = Der.encode(Der.SEQUENCE, Der.encode(Der.INTEGER, Arrays.copyOfRange(big, 1, 34)), Der.encode(Der.INTEGER, new byte[]{1}));
        assertEquals("crypto.ecdsa_der_invalid", code(() -> EcdsaSignatures.derToRaw(over)));
    }

    @Test
    @DisplayName("SSO-T-crypto-012 ECDSA DER with trailing bytes or bad length is rejected")
    void derTrailing() {
        byte[] good = Der.encode(Der.SEQUENCE, Der.encode(Der.INTEGER, new byte[]{1, 2, 3}), Der.encode(Der.INTEGER, new byte[]{4, 5, 6}));
        byte[] trailing = Arrays.copyOf(good, good.length + 1);
        assertEquals("crypto.ecdsa_der_invalid", code(() -> EcdsaSignatures.derToRaw(trailing)));
        byte[] badLen = good.clone();
        badLen[1] = (byte) (badLen[1] + 1);
        assertEquals("crypto.ecdsa_der_invalid", code(() -> EcdsaSignatures.derToRaw(badLen)));
        byte[] longForm = Bytes.concat(new byte[]{0x30, (byte) 0x81, good[1]}, Arrays.copyOfRange(good, 2, good.length));
        assertEquals("crypto.ecdsa_der_invalid", code(() -> EcdsaSignatures.derToRaw(longForm)));
    }

    @Test
    @DisplayName("SSO-T-crypto-013 raw ECDSA signature length and range checks")
    void rawChecks() {
        assertEquals("crypto.ecdsa_raw_invalid", code(() -> EcdsaSignatures.rawToDer(new byte[63])));
        assertEquals("crypto.ecdsa_raw_invalid", code(() -> EcdsaSignatures.rawToDer(new byte[65])));
        assertEquals("crypto.ecdsa_raw_invalid", code(() -> EcdsaSignatures.rawToDer(new byte[64])));
        byte[] high = new byte[64];
        Arrays.fill(high, (byte) 0xFF);
        assertEquals("crypto.ecdsa_raw_invalid", code(() -> EcdsaSignatures.rawToDer(high)));
    }

    @Test
    @DisplayName("SSO-T-crypto-014 verify returns false for DER-encoded or truncated signatures")
    void verifyWrongFormat() throws Exception {
        SigningKey k = SigningKey.generate(SigningAlgorithm.ES256);
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(k.privateKey());
        s.update(MSG);
        byte[] der = s.sign();
        assertFalse(k.verify(MSG, der));
        assertFalse(k.verify(MSG, Arrays.copyOf(k.sign(MSG), 63)));
        SigningKey rsa = SigningKey.generate(SigningAlgorithm.RS256);
        assertFalse(rsa.verify(MSG, Arrays.copyOf(rsa.sign(MSG), 255)));
        assertFalse(rsa.verify(MSG, null));
    }

    @Test
    @DisplayName("SSO-T-crypto-015 RFC 7638 section 3.1 thumbprint vector")
    void rfc7638() {
        SigningKey k = Jwk.parse(RfcVectors.RFC7638_RSA_JWK);
        assertEquals(RfcVectors.RFC7638_THUMBPRINT, k.thumbprint());
        assertEquals("2011-04-29", k.kid());
    }

    @Test
    @DisplayName("SSO-T-crypto-016 RFC 8037 A.3 Ed25519 thumbprint vector")
    void rfc8037Thumbprint() {
        SigningKey k = Jwk.parse(RfcVectors.RFC8037_JWK);
        assertEquals(RfcVectors.RFC8037_THUMBPRINT, k.thumbprint());
        assertEquals(RfcVectors.RFC8037_THUMBPRINT, k.kid());
    }

    @Test
    @DisplayName("SSO-T-crypto-017 kid defaults to RFC 7638 thumbprint for generated keys")
    void kidIsThumbprint() {
        for (SigningAlgorithm a : SigningAlgorithm.values()) {
            SigningKey k = SigningKey.generate(a);
            assertEquals(Jwk.thumbprint(k.publicKey()), k.kid());
            assertEquals(43, k.kid().length());
        }
    }

    @Test
    @DisplayName("SSO-T-crypto-018 RSA private JWK export and import round-trip")
    void rsaJwkRoundTrip() {
        SigningKey k = SigningKey.generate(SigningAlgorithm.RS256);
        Map<String, Object> jwk = Jwk.toPrivateJwk(k);
        SigningKey back = Jwk.parse(Json.write(jwk));
        assertEquals(k.kid(), back.kid());
        assertTrue(k.verify(MSG, back.sign(MSG)));
        Map<String, Object> pub = Jwk.toPublicJwk(k);
        assertFalse(Jwk.hasPrivateMembers(pub));
        assertFalse(Jwk.parse(pub).canSign());
    }

    @Test
    @DisplayName("SSO-T-crypto-019 EC P-256 private JWK round-trip")
    void ecJwkRoundTrip() {
        SigningKey k = SigningKey.generate(SigningAlgorithm.ES256);
        SigningKey back = Jwk.parse(Json.write(Jwk.toPrivateJwk(k)));
        assertEquals(k.kid(), back.kid());
        assertTrue(k.verify(MSG, back.sign(MSG)));
    }

    @Test
    @DisplayName("SSO-T-crypto-020 OKP Ed25519 private JWK round-trip")
    void okpJwkRoundTrip() {
        SigningKey k = SigningKey.generate(SigningAlgorithm.EdDSA);
        Map<String, Object> jwk = Jwk.toPrivateJwk(k);
        assertEquals("OKP", jwk.get("kty"));
        SigningKey back = Jwk.parse(jwk);
        assertEquals(k.kid(), back.kid());
        assertArrayEquals(k.sign(MSG), back.sign(MSG));
    }

    @Test
    @DisplayName("SSO-T-crypto-021 EC JWK with point not on curve is rejected (invalid curve attack)")
    void pointNotOnCurve() {
        Map<String, Object> jwk = Json.parseObject(RfcVectors.RFC7515_A3_JWK);
        jwk.remove("d");
        byte[] y = Bytes.b64urlDecode((String) jwk.get("y"));
        y[31] ^= 1;
        jwk.put("y", Bytes.b64url(y));
        assertEquals("crypto.point_invalid", code(() -> Jwk.parse(jwk)));
        byte[] p = Jwk.fixed(new BigInteger("115792089210356248762697446949407573530086143415290314195533631308867097853951"), 32);
        jwk.put("x", Bytes.b64url(p));
        assertEquals("crypto.point_invalid", code(() -> Jwk.parse(jwk)));
    }

    @Test
    @DisplayName("SSO-T-crypto-022 EC JWK coordinates must be exactly 32 bytes")
    void ecLength() {
        Map<String, Object> jwk = Json.parseObject(RfcVectors.RFC7515_A3_JWK);
        jwk.remove("d");
        jwk.put("x", Bytes.b64url(Arrays.copyOf(Bytes.b64urlDecode((String) jwk.get("x")), 31)));
        assertEquals("jwk.ec_length", code(() -> Jwk.parse(jwk)));
    }

    @Test
    @DisplayName("SSO-T-crypto-023 JWK alg conflicting with key type is rejected")
    void jwkAlgMismatch() {
        Map<String, Object> jwk = Json.parseObject(RfcVectors.RFC7515_A3_JWK);
        jwk.put("alg", "RS256");
        assertEquals("jwk.alg_mismatch", code(() -> Jwk.parse(jwk)));
        jwk.put("alg", "HS256");
        assertEquals("jwk.alg_mismatch", code(() -> Jwk.parse(jwk)));
    }

    @Test
    @DisplayName("SSO-T-crypto-024 JWK with use=enc or unsupported kty is rejected")
    void jwkUse() {
        Map<String, Object> jwk = Json.parseObject(RfcVectors.RFC7515_A3_JWK);
        jwk.put("use", "enc");
        assertEquals("jwk.use_mismatch", code(() -> Jwk.parse(jwk)));
        assertEquals("jwk.unsupported_kty", code(() -> Jwk.parse("{\"kty\":\"oct\",\"k\":\"AAAA\"}")));
    }

    @Test
    @DisplayName("SSO-T-crypto-025 RSA JWK with partial CRT parameters or multi-prime is rejected")
    void rsaPartialCrt() {
        Map<String, Object> jwk = Json.parseObject(RfcVectors.RFC7515_A2_JWK);
        jwk.remove("qi");
        assertEquals("jwk.rsa_crt_incomplete", code(() -> Jwk.parse(jwk)));
        Map<String, Object> multi = Json.parseObject(RfcVectors.RFC7515_A2_JWK);
        multi.put("oth", java.util.List.of());
        assertEquals("jwk.rsa_multiprime", code(() -> Jwk.parse(multi)));
    }

    @Test
    @DisplayName("SSO-T-crypto-026 RSA JWK modulus with leading zero octet is rejected")
    void rsaLeadingZero() {
        Map<String, Object> jwk = Json.parseObject(RfcVectors.RFC7638_RSA_JWK);
        jwk.put("n", Bytes.b64url(Bytes.concat(new byte[]{0}, Bytes.b64urlDecode((String) jwk.get("n")))));
        assertEquals("jwk.bad_integer", code(() -> Jwk.parse(jwk)));
    }

    @Test
    @DisplayName("SSO-T-crypto-027 PEM PKCS#8 and SPKI round-trip for RSA, EC and Ed25519")
    void pemRoundTrip() {
        for (SigningAlgorithm a : SigningAlgorithm.values()) {
            SigningKey k = SigningKey.generate(a);
            String priv = Pem.encodePrivateKey(k.privateKey());
            String pub = Pem.encodePublicKey(k.publicKey());
            assertTrue(priv.startsWith("-----BEGIN PRIVATE KEY-----\n"));
            assertTrue(pub.startsWith("-----BEGIN PUBLIC KEY-----\n"));
            SigningKey back = Pem.signingKey(priv, pub);
            assertEquals(a, back.alg());
            assertEquals(k.kid(), back.kid());
            assertEquals(k.kid(), Pem.verifyingKey(pub).kid());
        }
    }

    @Test
    @DisplayName("SSO-T-crypto-028 PEM RSA private key alone derives the public key (rsa.private-key files)")
    void pemRsaDerive() {
        SigningKey k = SigningKey.generate(SigningAlgorithm.RS256);
        String priv = Pem.encodePrivateKey(k.privateKey()).replace("\n", "\r\n");
        SigningKey back = Pem.signingKey(priv, null);
        assertEquals(k.kid(), back.kid());
    }

    @Test
    @DisplayName("SSO-T-crypto-029 PEM EC private key without public key is refused")
    void pemEcNeedsPublic() {
        SigningKey k = SigningKey.generate(SigningAlgorithm.ES256);
        assertEquals("pem.public_key_required", code(() -> Pem.signingKey(Pem.encodePrivateKey(k.privateKey()), null)));
    }

    @Test
    @DisplayName("SSO-T-crypto-030 malformed PEM is rejected")
    void pemMalformed() {
        SigningKey k = SigningKey.generate(SigningAlgorithm.EdDSA);
        String pub = Pem.encodePublicKey(k.publicKey());
        assertEquals("pem.invalid", code(() -> Pem.parsePublicKey(pub.replace("END PUBLIC", "END PRIVATE"))));
        assertEquals("pem.invalid", code(() -> Pem.parsePublicKey(pub.replace("MC", "M!"))));
        assertEquals("pem.invalid", code(() -> Pem.parsePublicKey("-----BEGIN PUBLIC KEY-----\n-----END PUBLIC KEY-----")));
        assertEquals("pem.label_unsupported", code(() -> Pem.parsePublicKey(pub.replace("PUBLIC KEY", "CERTIFICATE"))));
        String body = java.util.Base64.getEncoder().encodeToString(k.publicKey().getEncoded());
        assertTrue(body.endsWith("="));
        int i = body.indexOf('=') - 1;
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        char bumped = alphabet.charAt(alphabet.indexOf(body.charAt(i)) + 1);
        String nonCanonical = body.substring(0, i) + bumped + body.substring(i + 1);
        assertEquals("pem.invalid", code(() -> Pem.parsePublicKey("-----BEGIN PUBLIC KEY-----\n" + nonCanonical + "\n-----END PUBLIC KEY-----")));
    }

    @Test
    @DisplayName("SSO-T-crypto-031 PEM PKCS#1 RSA PRIVATE KEY is accepted")
    void pemPkcs1() {
        SigningKey k = SigningKey.generate(SigningAlgorithm.RS256);
        Der outer = new Der(k.privateKey().getEncoded());
        Der seq = outer.read(Der.SEQUENCE).reader();
        seq.read(Der.INTEGER);
        seq.read(Der.SEQUENCE);
        byte[] pkcs1 = seq.read(Der.OCTET_STRING).value();
        SigningKey back = Pem.signingKey(Pem.encode("RSA PRIVATE KEY", pkcs1), null);
        assertEquals(k.kid(), back.kid());
    }

    @Test
    @DisplayName("SSO-T-crypto-032 toString never prints key material and verify-only keys cannot sign")
    void noLeak() {
        SigningKey k = SigningKey.generate(SigningAlgorithm.ES256);
        assertFalse(k.toString().contains(Bytes.b64url(k.privateKey().getEncoded())));
        assertTrue(k.toString().startsWith("SigningKey[ES256,"));
        assertEquals("crypto.verify_only_key", code(() -> k.publicOnly().sign(MSG)));
        assertEquals("crypto.verify_only_key", code(() -> Jwk.toPrivateJwk(k.publicOnly())));
    }

    @Test
    @DisplayName("SSO-T-crypto-033 RFC 7515 A.2 RS256 signature reproduced exactly")
    void rfc7515A2() {
        SigningKey k = Jwk.parse(RfcVectors.RFC7515_A2_JWK);
        byte[] sig = k.sign(RfcVectors.RFC7515_A2_SIGNING_INPUT.getBytes(StandardCharsets.US_ASCII));
        assertEquals(RfcVectors.RFC7515_A2_SIGNATURE, Bytes.b64url(sig));
    }

    @Test
    @DisplayName("SSO-T-crypto-034 RFC 8037 A.4 Ed25519 signature reproduced exactly")
    void rfc8037A4() {
        SigningKey k = Jwk.parse(RfcVectors.RFC8037_JWK);
        String[] parts = RfcVectors.RFC8037_TOKEN.split("\\.");
        byte[] sig = k.sign((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertEquals(parts[2], Bytes.b64url(sig));
    }

    @Test
    @DisplayName("SSO-T-crypto-035 kid validation rejects control characters and overlong values")
    void kidValidation() {
        assertFalse(SigningKey.isValidKid("a\nb"));
        assertFalse(SigningKey.isValidKid("x".repeat(129)));
        assertFalse(SigningKey.isValidKid(""));
        assertTrue(SigningKey.isValidKid("2011-04-29"));
        SigningKey k = SigningKey.generate(SigningAlgorithm.EdDSA);
        assertEquals("crypto.kid_invalid", code(() -> k.withKid("bad kid")));
    }
}
