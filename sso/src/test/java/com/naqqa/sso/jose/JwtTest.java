package com.naqqa.sso.jose;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.crypto.KeyRing;
import com.naqqa.sso.crypto.MutableClock;
import com.naqqa.sso.crypto.SigningAlgorithm;
import com.naqqa.sso.crypto.SigningKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtTest {
    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");
    private static final long T = NOW.getEpochSecond();

    private final MutableClock clock = new MutableClock(NOW);
    private final SigningKey key = SigningKey.generate(SigningAlgorithm.ES256);
    private final KeyRing ring = KeyRing.of(clock, key);

    private static String code(Runnable r) {
        return assertThrows(SsoException.class, r::run).code();
    }

    private Map<String, Object> claims(long iat, long exp) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("iss", "https://sso.naqqa.com");
        c.put("sub", "user-1");
        c.put("aud", "app-1");
        c.put("iat", iat);
        c.put("exp", exp);
        return c;
    }

    private JwtValidator.Builder validator() {
        return JwtValidator.builder(clock).issuer("https://sso.naqqa.com").audience("app-1");
    }

    @Test
    @DisplayName("SSO-T-jose-024 valid at+jwt access token passes all checks")
    void valid() {
        String t = Jwt.sign(ring, Jwt.TYP_ACCESS_TOKEN, claims(T, T + 300));
        Jwt.Verified v = validator().type("at+jwt").maxLifetime(Duration.ofHours(1)).build().verify(t, ring);
        assertEquals("user-1", v.claims().subject());
        assertEquals(key.kid(), v.kid());
        assertEquals(Instant.ofEpochSecond(T + 300), v.claims().expiresAt());
    }

    @Test
    @DisplayName("SSO-T-jose-025 issuer must match exactly")
    void issuer() {
        Map<String, Object> c = claims(T, T + 60);
        c.put("iss", "https://sso.naqqa.com/");
        String t = Jwt.sign(ring, null, c);
        assertEquals("jwt.issuer_mismatch", code(() -> validator().build().verify(t, ring)));
        c.remove("iss");
        String t2 = Jwt.sign(ring, null, c);
        assertEquals("jwt.issuer_mismatch", code(() -> validator().build().verify(t2, ring)));
    }

    @Test
    @DisplayName("SSO-T-jose-026 audience may be string or array and must contain the expected value")
    void audience() {
        Map<String, Object> c = claims(T, T + 60);
        c.put("aud", List.of("other", "app-1"));
        validator().build().verify(Jwt.sign(ring, null, c), ring);
        c.put("aud", List.of("other"));
        String t = Jwt.sign(ring, null, c);
        assertEquals("jwt.audience_mismatch", code(() -> validator().build().verify(t, ring)));
        c.put("aud", 5);
        String t2 = Jwt.sign(ring, null, c);
        assertEquals("jwt.claim_invalid", code(() -> validator().build().verify(t2, ring)));
    }

    @Test
    @DisplayName("SSO-T-jose-027 exp edge: rejected at exactly exp plus leeway, accepted one second before")
    void expEdge() {
        String t = Jwt.sign(ring, null, claims(T - 600, T - 60));
        assertEquals("jwt.expired", code(() -> validator().build().verify(t, ring)));
        String ok = Jwt.sign(ring, null, claims(T - 600, T - 59));
        validator().build().verify(ok, ring);
    }

    @Test
    @DisplayName("SSO-T-jose-028 exp edge with zero leeway: now == exp is expired")
    void expZeroLeeway() {
        String t = Jwt.sign(ring, null, claims(T - 10, T));
        assertEquals("jwt.expired", code(() -> validator().leeway(Duration.ZERO).build().verify(t, ring)));
        String ok = Jwt.sign(ring, null, claims(T - 10, T + 1));
        validator().leeway(Duration.ZERO).build().verify(ok, ring);
    }

    @Test
    @DisplayName("SSO-T-jose-029 nbf edge: accepted at nbf == now + leeway, rejected one second later")
    void nbfEdge() {
        Map<String, Object> c = claims(T, T + 600);
        c.put("nbf", T + 60);
        validator().build().verify(Jwt.sign(ring, null, c), ring);
        c.put("nbf", T + 61);
        String t = Jwt.sign(ring, null, c);
        assertEquals("jwt.not_yet_valid", code(() -> validator().build().verify(t, ring)));
    }

    @Test
    @DisplayName("SSO-T-jose-030 iat in the future beyond leeway is rejected")
    void iatFuture() {
        String t = Jwt.sign(ring, null, claims(T + 61, T + 600));
        assertEquals("jwt.iat_future", code(() -> validator().build().verify(t, ring)));
        validator().build().verify(Jwt.sign(ring, null, claims(T + 60, T + 600)), ring);
    }

    @Test
    @DisplayName("SSO-T-jose-031 max lifetime is enforced from iat (or now when iat absent)")
    void maxLifetime() {
        String t = Jwt.sign(ring, null, claims(T, T + 3601));
        assertEquals("jwt.lifetime_exceeded", code(() -> validator().maxLifetime(Duration.ofHours(1)).build().verify(t, ring)));
        Map<String, Object> c = claims(T, T + 7200);
        c.remove("iat");
        String t2 = Jwt.sign(ring, null, c);
        assertEquals("jwt.lifetime_exceeded", code(() -> validator().maxLifetime(Duration.ofHours(1)).build().verify(t2, ring)));
        validator().maxLifetime(Duration.ofHours(1)).build().verify(Jwt.sign(ring, null, claims(T, T + 3600)), ring);
    }

    @Test
    @DisplayName("SSO-T-jose-032 exp is required by default; iat optional unless required")
    void required() {
        Map<String, Object> c = claims(T, T + 60);
        c.remove("exp");
        String t = Jwt.sign(ring, null, c);
        assertEquals("jwt.exp_missing", code(() -> validator().build().verify(t, ring)));
        validator().requireExp(false).build().verify(t, ring);
        Map<String, Object> c2 = claims(T, T + 60);
        c2.remove("iat");
        String t2 = Jwt.sign(ring, null, c2);
        assertEquals("jwt.iat_missing", code(() -> validator().requireIat(true).build().verify(t2, ring)));
        assertEquals("jwt.claim_missing", code(() -> validator().requireClaim("jti").build().verify(t2, ring)));
    }

    @Test
    @DisplayName("SSO-T-jose-033 typ check: at+jwt and application/at+jwt accepted, JWT rejected")
    void typ() {
        JwtValidator v = validator().type("at+jwt").build();
        v.verify(Jwt.sign(ring, "application/at+jwt", claims(T, T + 60)), ring);
        v.verify(Jwt.sign(ring, "AT+JWT", claims(T, T + 60)), ring);
        String jwt = Jwt.sign(ring, "JWT", claims(T, T + 60));
        assertEquals("jwt.typ_mismatch", code(() -> v.verify(jwt, ring)));
        String none = Jwt.sign(ring, null, claims(T, T + 60));
        assertEquals("jwt.typ_mismatch", code(() -> v.verify(none, ring)));
    }

    @Test
    @DisplayName("SSO-T-jose-034 malformed numeric dates are rejected")
    void badDates() {
        Map<String, Object> c = claims(T, T + 60);
        c.put("exp", "tomorrow");
        String t = Jwt.sign(ring, null, c);
        assertEquals("jwt.claim_invalid", code(() -> validator().build().verify(t, ring)));
        c.put("exp", Long.MAX_VALUE);
        String t2 = Jwt.sign(ring, null, c);
        assertEquals("jwt.claim_invalid", code(() -> validator().build().verify(t2, ring)));
        c.put("exp", -5L);
        String t3 = Jwt.sign(ring, null, c);
        assertEquals("jwt.claim_invalid", code(() -> validator().build().verify(t3, ring)));
        c.put("exp", new BigDecimal("1e400"));
        String t4 = Jwt.sign(ring, null, c);
        assertEquals("jwt.claim_invalid", code(() -> validator().build().verify(t4, ring)));
    }

    @Test
    @DisplayName("SSO-T-jose-035 fractional numeric dates are floored")
    void fractionalDates() {
        Map<String, Object> c = claims(T, T + 60);
        c.put("exp", new BigDecimal(T + 120 + ".9"));
        Jwt.Verified v = validator().build().verify(Jwt.sign(ring, null, c), ring);
        assertEquals(Instant.ofEpochSecond(T + 120), v.claims().expiresAt());
    }

    @Test
    @DisplayName("SSO-T-jose-036 leeway is configurable and capped at 5 minutes")
    void leewayConfig() {
        assertEquals("jwt.leeway_invalid", code(() -> JwtValidator.builder(clock).leeway(Duration.ofMinutes(6))));
        assertEquals("jwt.leeway_invalid", code(() -> JwtValidator.builder(clock).leeway(Duration.ofSeconds(-1))));
        String t = Jwt.sign(ring, null, claims(T - 600, T - 100));
        validator().leeway(Duration.ofSeconds(120)).build().verify(t, ring);
        assertEquals(Duration.ofSeconds(60), JwtValidator.DEFAULT_LEEWAY);
    }

    @Test
    @DisplayName("SSO-T-jose-037 payload that is not a JSON object is rejected as claims")
    void claimsShape() {
        String t = Jws.sign(ring.active(), "[1,2]".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("jwt.claims_invalid", code(() -> validator().build().verify(t, ring)));
        String dup = Jws.sign(ring.active(), "{\"sub\":\"a\",\"sub\":\"admin\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("jwt.claims_invalid", code(() -> validator().build().verify(dup, ring)));
    }

    @Test
    @DisplayName("SSO-T-jose-050 validator refuses to build without issuer or audience unless audience is explicitly waived")
    void validatorRequiresIssuerAudience() {
        assertEquals("jwt.issuer_required", code(() -> JwtValidator.builder(clock).audience("app-1").build()));
        assertEquals("jwt.audience_required", code(() -> JwtValidator.builder(clock).issuer("https://sso.naqqa.com").build()));
        JwtValidator v = JwtValidator.builder(clock).issuer("https://sso.naqqa.com").anyAudience().build();
        v.verify(Jwt.sign(ring, null, claims(T, T + 60)), ring);
    }
}
