package com.naqqa.sso.jose;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.crypto.Jwk;
import com.naqqa.sso.crypto.KeyRing;
import com.naqqa.sso.crypto.MutableClock;
import com.naqqa.sso.crypto.Pem;
import com.naqqa.sso.crypto.SigningAlgorithm;
import com.naqqa.sso.crypto.SigningKey;
import com.naqqa.sso.json.Json;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwsTest {
    private static final byte[] PAYLOAD = "{\"sub\":\"u1\"}".getBytes(StandardCharsets.UTF_8);

    private static String code(Runnable r) {
        return assertThrows(SsoException.class, r::run).code();
    }

    static String craft(Map<String, Object> header, byte[] payload, SigningKey signer) {
        String input = Bytes.b64url(Json.writeUtf8(header)) + "." + Bytes.b64url(payload);
        byte[] sig = signer == null ? new byte[0] : signer.sign(input.getBytes(StandardCharsets.US_ASCII));
        return input + "." + Bytes.b64url(sig);
    }

    private static Map<String, Object> header(String alg, String kid) {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("alg", alg);
        if (kid != null) {
            h.put("kid", kid);
        }
        return h;
    }

    private static KeyRing ring(SigningKey k) {
        return KeyRing.of(new MutableClock(Instant.parse("2026-01-01T00:00:00Z")), k);
    }

    @Test
    @DisplayName("SSO-T-jose-001 sign and verify round-trip for RS256, ES256, EdDSA via KeyRing")
    void roundTrip() {
        for (SigningAlgorithm a : SigningAlgorithm.values()) {
            SigningKey k = SigningKey.generate(a);
            String token = Jws.sign(k, PAYLOAD);
            Jws.Verified v = Jws.verify(token, ring(k));
            assertEquals(a.jwa(), v.header().get("alg"));
            assertEquals(k.kid(), v.header().get("kid"));
            assertEquals("{\"sub\":\"u1\"}", v.payloadUtf8());
            assertFalse(v.key().canSign());
        }
    }

    @Test
    @DisplayName("SSO-T-jose-002 RFC 7515 A.2 RS256 token verifies")
    void rfc7515A2() {
        SigningKey k = Jwk.parse(RfcVectors.RFC7515_A2_JWK);
        String token = RfcVectors.RFC7515_A2_SIGNING_INPUT + "." + RfcVectors.RFC7515_A2_SIGNATURE;
        Jws.Verified v = Jws.verify(token, k.publicOnly());
        assertTrue(v.payloadUtf8().contains("\"iss\":\"joe\""));
    }

    @Test
    @DisplayName("SSO-T-jose-003 RFC 7515 A.3 ES256 token verifies")
    void rfc7515A3() {
        SigningKey k = Jwk.parse(RfcVectors.RFC7515_A3_JWK);
        Jws.Verified v = Jws.verify(RfcVectors.RFC7515_A3_TOKEN, k.publicOnly());
        assertEquals("ES256", v.header().get("alg"));
    }

    @Test
    @DisplayName("SSO-T-jose-004 RFC 8037 A.4 EdDSA token verifies")
    void rfc8037() {
        SigningKey k = Jwk.parse(RfcVectors.RFC8037_JWK);
        assertEquals("Example of Ed25519 signing", Jws.verify(RfcVectors.RFC8037_TOKEN, k.publicOnly()).payloadUtf8());
    }

    @Test
    @DisplayName("SSO-T-jose-005 RFC 7520 4.1 RS256 token with kid verifies through KeyRing")
    void rfc7520() {
        SigningKey k = Jwk.parse(RfcVectors.RFC7520_RSA_JWK);
        assertEquals("bilbo.baggins@hobbiton.example", k.kid());
        Jws.Verified v = Jws.verify(RfcVectors.RFC7520_4_1_TOKEN, KeyRing.verifyOnly(new MutableClock(Instant.EPOCH), List.of(k)));
        assertTrue(v.payloadUtf8().startsWith("It’s a dangerous business, Frodo"));
    }

    @Test
    @DisplayName("SSO-T-jose-006 alg none with empty signature is rejected")
    void algNoneEmptySig() {
        SigningKey k = SigningKey.generate(SigningAlgorithm.RS256);
        String token = craft(header("none", k.kid()), PAYLOAD, null);
        assertEquals("jose.signature_missing", code(() -> Jws.verify(token, ring(k))));
    }

    @Test
    @DisplayName("SSO-T-jose-007 alg none in any case with a signature is rejected")
    void algNoneVariants() {
        SigningKey k = SigningKey.generate(SigningAlgorithm.RS256);
        for (String alg : new String[]{"none", "None", "NONE", "nOnE"}) {
            String input = Bytes.b64url(Json.writeUtf8(header(alg, k.kid()))) + "." + Bytes.b64url(PAYLOAD);
            String token = input + "." + Bytes.b64url(new byte[]{1});
            assertEquals("jose.alg_none", code(() -> Jws.verify(token, ring(k))));
        }
    }

    @Test
    @DisplayName("SSO-T-jose-008 HS256 signed with the RSA public key is rejected (key confusion)")
    void hs256Confusion() {
        SigningKey k = SigningKey.generate(SigningAlgorithm.RS256);
        byte[] secret = Pem.encodePublicKey(k.publicKey()).getBytes(StandardCharsets.US_ASCII);
        String input = Bytes.b64url(Json.writeUtf8(header("HS256", k.kid()))) + "." + Bytes.b64url(PAYLOAD);
        String token = input + "." + Bytes.b64url(Hashes.hmacSha256(secret, input.getBytes(StandardCharsets.US_ASCII)));
        assertEquals("jose.alg_mismatch", code(() -> Jws.verify(token, ring(k))));
        assertEquals("jose.alg_mismatch", code(() -> Jws.verify(token, k.publicOnly())));
    }

    @Test
    @DisplayName("SSO-T-jose-009 alg header differing from key algorithm is rejected")
    void algMismatch() {
        SigningKey es = SigningKey.generate(SigningAlgorithm.ES256);
        String token = craft(header("RS256", es.kid()), PAYLOAD, es);
        assertEquals("jose.alg_mismatch", code(() -> Jws.verify(token, ring(es))));
        String eddsa = craft(header("EdDSA", es.kid()), PAYLOAD, es);
        assertEquals("jose.alg_mismatch", code(() -> Jws.verify(eddsa, ring(es))));
    }

    @Test
    @DisplayName("SSO-T-jose-010 kid injection: path, SQL and unknown kids are not resolved")
    void kidInjection() {
        SigningKey k = SigningKey.generate(SigningAlgorithm.ES256);
        for (String kid : new String[]{"../../../../dev/null", "'OR'1'='1'--", "http://evil.example/key", k.kid() + "x"}) {
            String token = craft(header("ES256", kid), PAYLOAD, k);
            assertEquals("jose.kid_unknown", code(() -> Jws.verify(token, ring(k))));
        }
    }

    @Test
    @DisplayName("SSO-T-jose-011 kid with control chars, non-string type or missing is rejected")
    void kidInvalid() {
        SigningKey k = SigningKey.generate(SigningAlgorithm.ES256);
        assertEquals("jose.kid_invalid", code(() -> Jws.verify(craft(header("ES256", "a\u0000b"), PAYLOAD, k), ring(k))));
        Map<String, Object> h = header("ES256", null);
        h.put("kid", 7);
        assertEquals("jose.kid_invalid", code(() -> Jws.verify(craft(h, PAYLOAD, k), ring(k))));
        assertEquals("jose.kid_missing", code(() -> Jws.verify(craft(header("ES256", null), PAYLOAD, k), ring(k))));
        assertEquals("jose.kid_mismatch", code(() -> Jws.verify(craft(header("ES256", "other"), PAYLOAD, k), k)));
    }

    @Test
    @DisplayName("SSO-T-jose-012 signature stripping is rejected")
    void signatureStripping() {
        SigningKey k = SigningKey.generate(SigningAlgorithm.EdDSA);
        String token = Jws.sign(k, PAYLOAD);
        String stripped = token.substring(0, token.lastIndexOf('.') + 1);
        assertEquals("jose.signature_missing", code(() -> Jws.verify(stripped, ring(k))));
        String noDot = token.substring(0, token.lastIndexOf('.'));
        assertEquals("jose.malformed", code(() -> Jws.verify(noDot, ring(k))));
    }

    @Test
    @DisplayName("SSO-T-jose-013 truncated or extra segments are rejected")
    void truncatedSegments() {
        SigningKey k = SigningKey.generate(SigningAlgorithm.EdDSA);
        String token = Jws.sign(k, PAYLOAD);
        assertEquals("jose.malformed", code(() -> Jws.verify(token + ".x", ring(k))));
        assertEquals("jose.malformed", code(() -> Jws.verify(token.substring(token.indexOf('.')), ring(k))));
        assertEquals("jose.malformed", code(() -> Jws.verify("", ring(k))));
        assertEquals("jose.malformed", code(() -> Jws.verify(null, ring(k))));
        String cut = token.substring(0, token.length() - 4);
        String c = code(() -> Jws.verify(cut, ring(k)));
        assertTrue(c.equals("jose.signature_invalid") || c.equals("jose.bad_base64"));
        String headerCut = token.substring(0, 5) + token.substring(token.indexOf('.'));
        String hc = code(() -> Jws.verify(headerCut, ring(k)));
        assertTrue(hc.equals("jose.header_invalid") || hc.equals("jose.bad_base64"));
    }

    @Test
    @DisplayName("SSO-T-jose-014 non-canonical base64url (padding, +/, trailing bits) is rejected")
    void nonCanonicalBase64() {
        SigningKey k = SigningKey.generate(SigningAlgorithm.EdDSA);
        String token = Jws.sign(k, PAYLOAD);
        assertEquals("jose.bad_base64", code(() -> Jws.verify(token + "=", ring(k))));
        String sig = token.substring(token.lastIndexOf('.') + 1);
        String body = token.substring(0, token.lastIndexOf('.') + 1);
        assertEquals("jose.bad_base64", code(() -> Jws.verify(body + sig.replace('-', '+').replace('_', '/') + "+", ring(k))));
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        char last = sig.charAt(sig.length() - 1);
        String bumped = sig.substring(0, sig.length() - 1) + alphabet.charAt(alphabet.indexOf(last) + 1);
        assertEquals("jose.bad_base64", code(() -> Jws.verify(body + bumped, ring(k))));
    }

    @Test
    @DisplayName("SSO-T-jose-015 tokens above 16 KiB are rejected on sign and verify")
    void oversized() {
        SigningKey k = SigningKey.generate(SigningAlgorithm.EdDSA);
        byte[] big = new byte[13 * 1024];
        assertEquals("jose.too_large", code(() -> Jws.sign(k, big)));
        String huge = "a".repeat(Jws.MAX_TOKEN_SIZE + 1);
        assertEquals("jose.too_large", code(() -> Jws.verify(huge, ring(k))));
    }

    @Test
    @DisplayName("SSO-T-jose-016 jku, x5u, jwk, x5c and b64 headers are rejected")
    void forbiddenHeaders() {
        SigningKey k = SigningKey.generate(SigningAlgorithm.ES256);
        for (String name : new String[]{"jku", "x5u", "jwk", "x5c", "b64"}) {
            Map<String, Object> h = header("ES256", k.kid());
            h.put(name, "b64".equals(name) ? Boolean.FALSE : "https://evil.example");
            assertEquals("jose.header_forbidden", code(() -> Jws.verify(craft(h, PAYLOAD, k), ring(k))));
        }
    }

    @Test
    @DisplayName("SSO-T-jose-017 crit headers are rejected as unsupported")
    void crit() {
        SigningKey k = SigningKey.generate(SigningAlgorithm.ES256);
        Map<String, Object> h = header("ES256", k.kid());
        h.put("crit", List.of("exp"));
        h.put("exp", 1);
        assertEquals("jose.crit_unsupported", code(() -> Jws.verify(craft(h, PAYLOAD, k), ring(k))));
    }

    @Test
    @DisplayName("SSO-T-jose-018 duplicate alg members in header are rejected")
    void duplicateHeader() {
        SigningKey k = SigningKey.generate(SigningAlgorithm.ES256);
        String headerJson = "{\"alg\":\"ES256\",\"kid\":\"" + k.kid() + "\",\"alg\":\"none\"}";
        String input = Bytes.b64url(headerJson.getBytes(StandardCharsets.UTF_8)) + "." + Bytes.b64url(PAYLOAD);
        String token = input + "." + Bytes.b64url(k.sign(input.getBytes(StandardCharsets.US_ASCII)));
        assertEquals("jose.header_invalid", code(() -> Jws.verify(token, ring(k))));
    }

    @Test
    @DisplayName("SSO-T-jose-019 header that is not a JSON object or lacks alg is rejected")
    void headerShape() {
        SigningKey k = SigningKey.generate(SigningAlgorithm.ES256);
        String arr = Bytes.b64url("[1]".getBytes(StandardCharsets.UTF_8)) + "." + Bytes.b64url(PAYLOAD) + ".AAAA";
        assertEquals("jose.header_invalid", code(() -> Jws.verify(arr, ring(k))));
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("kid", k.kid());
        assertEquals("jose.alg_missing", code(() -> Jws.verify(craft(h, PAYLOAD, k), ring(k))));
    }

    @Test
    @DisplayName("SSO-T-jose-020 payload or header modification invalidates the signature")
    void tamper() {
        SigningKey k = SigningKey.generate(SigningAlgorithm.RS256);
        String token = Jws.sign(k, PAYLOAD);
        String[] p = token.split("\\.");
        String forged = p[0] + "." + Bytes.b64url("{\"sub\":\"admin\"}".getBytes(StandardCharsets.UTF_8)) + "." + p[2];
        assertEquals("jose.signature_invalid", code(() -> Jws.verify(forged, ring(k))));
        SigningKey other = SigningKey.generate(SigningAlgorithm.RS256);
        String wrongKey = craft(header("RS256", k.kid()), PAYLOAD, other);
        assertEquals("jose.signature_invalid", code(() -> Jws.verify(wrongKey, ring(k))));
    }

    @Test
    @DisplayName("SSO-T-jose-021 signer refuses reserved or dangerous extra headers")
    void signerHeaders() {
        SigningKey k = SigningKey.generate(SigningAlgorithm.EdDSA);
        for (String name : new String[]{"alg", "kid", "crit", "jku", "jwk"}) {
            assertEquals("jose.header_forbidden", code(() -> Jws.sign(k, Map.of(name, "x"), PAYLOAD)));
        }
        assertEquals("jose.key_cannot_sign", code(() -> Jws.sign(k.publicOnly(), PAYLOAD)));
    }

    @Test
    @DisplayName("SSO-T-jose-022 retired key verifies until notAfter then is refused")
    void retiredKey() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        SigningKey a = SigningKey.generate(SigningAlgorithm.ES256);
        KeyRing ring = KeyRing.of(clock, a);
        String old = Jws.sign(ring.active(), PAYLOAD);
        ring.rotate(SigningKey.generate(SigningAlgorithm.ES256), java.time.Duration.ofMinutes(10));
        Jws.verify(old, ring);
        clock.advance(java.time.Duration.ofMinutes(10));
        assertEquals("jose.kid_unknown", code(() -> Jws.verify(old, ring)));
    }

    @Test
    @DisplayName("SSO-T-jose-023 JWE compact (5 segments) is not accepted as JWS")
    void jweAsJws() {
        SigningKey k = SigningKey.generate(SigningAlgorithm.ES256);
        String jwe = Jwe.encryptDir(Bytes.random(32), PAYLOAD, null);
        assertEquals("jose.malformed", code(() -> Jws.verify(jwe, ring(k))));
    }
}
