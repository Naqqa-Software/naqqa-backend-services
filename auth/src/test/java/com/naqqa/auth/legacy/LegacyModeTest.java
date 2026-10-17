package com.naqqa.auth.legacy;

import com.fasterxml.jackson.databind.JsonNode;
import com.naqqa.auth.entity.auth.RefreshTokenEntity;
import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.repository.RefreshTokenRepository;
import com.naqqa.auth.repository.redis.RegisterRecordRepository;
import com.naqqa.auth.service.security.DeviceSessionService;
import com.naqqa.auth.service.security.PasswordService;
import com.naqqa.auth.support.Browser;
import com.naqqa.auth.support.CapturingEmailService;
import com.naqqa.auth.support.Jwts;
import com.naqqa.auth.support.SsoTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyModeTest extends SsoTestBase {

    @Autowired
    RefreshTokenRepository refreshTokenRepository;
    @Autowired
    RegisterRecordRepository registerRecordRepository;
    @Autowired
    CapturingEmailService mails;
    @Autowired
    DeviceSessionService deviceSessionService;

    @Test
    @DisplayName("SSO-T-auth-010 legacy login keeps the AuthResponse shape and issues an RS256 token with the original claims")
    void loginShapeAndClaims() {
        String email = uniqueEmail("legacy");
        user(email);
        Browser b = browser();
        MvcResult r = login(b, email, PASSWORD);
        assertEquals(200, status(r));
        JsonNode body = json(r);
        assertEquals(email, body.get("email").asText());
        assertTrue(body.has("authorities"));
        assertFalse(body.has("status"));
        String token = b.cookie("access_token");
        JsonNode header = Jwts.header(token);
        JsonNode claims = Jwts.payload(token);
        assertEquals("RS256", header.get("alg").asText());
        assertEquals("JWT", header.get("typ").asText());
        assertEquals("naqqa-auth", claims.get("iss").asText());
        assertEquals(body.get("id").asLong(), claims.get("id").asLong());
        assertEquals(email, claims.get("email").asText());
        assertTrue(claims.has("fullName"));
        assertTrue(claims.get("roles").isArray());
        assertTrue(claims.get("authorities").isArray());
        assertEquals("USER", claims.get("role").get("name").asText());
        assertTrue(Jwts.verifiesWithJdk(token, Jwts.testPublicKey()));
        assertNotNull(b.cookie("refresh_token"));
        assertEquals(43, b.cookie("refresh_token").length());
    }

    @Test
    @DisplayName("SSO-T-auth-011 access_token cookie and Bearer header both authenticate and map authorities for @PreAuthorize")
    void cookieAndBearer() {
        String email = uniqueEmail("legacy");
        user(email);
        Browser b = loggedIn(email);
        MvcResult me = b.get("/api/auth/test/me");
        assertEquals(200, status(me));
        assertTrue(json(me).get("authorities").toString().contains("profile:read"));
        String token = b.cookie("access_token");
        Browser other = browser().bearer(token);
        assertEquals(200, status(other.get("/api/auth/test/me")));
        assertEquals(401, status(browser().get("/api/auth/test/me")));
    }

    @Test
    @DisplayName("SSO-T-auth-012 tokens signed by another key, alg none, expired or garbage are rejected with 401")
    void badTokens() throws Exception {
        String email = uniqueEmail("legacy");
        user(email);
        Browser b = loggedIn(email);
        String token = b.cookie("access_token");
        JsonNode claims = Jwts.payload(token);
        java.security.KeyPairGenerator g = java.security.KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        String forged = Jwts.sign("{\"alg\":\"RS256\",\"typ\":\"JWT\"}", claims.toString(), g.generateKeyPair().getPrivate());
        assertEquals(401, status(browser().bearer(forged).get("/api/auth/test/me")));
        String none = token.split("\\.")[0].replace(token.split("\\.")[0], com.naqqa.sso.util.Bytes.b64url("{\"alg\":\"none\"}".getBytes()))
                + "." + token.split("\\.")[1] + ".";
        assertEquals(401, status(browser().bearer(none).get("/api/auth/test/me")));
        assertEquals(401, status(browser().bearer("not.a.jwt").get("/api/auth/test/me")));
        Map<String, Object> expired = new LinkedHashMap<>();
        Browser.json(b.get("/api/auth/test/me"));
        long now = Instant.now().getEpochSecond();
        String exp = Jwts.sign("{\"alg\":\"RS256\",\"typ\":\"JWT\"}", claims.toString()
                .replaceFirst("\"exp\":\\d+", "\"exp\":" + (now - 3600)).replaceFirst("\"iat\":\\d+", "\"iat\":" + (now - 7200)), Jwts.testPrivateKey());
        assertEquals(401, status(browser().bearer(exp).get("/api/auth/test/me")));
        assertTrue(expired.isEmpty());
    }

    @Test
    @DisplayName("SSO-T-auth-013 a token without kid header (pre-migration Nimbus style) still verifies against the active key")
    void tokenWithoutKid() {
        String email = uniqueEmail("legacy");
        user(email);
        Browser b = loggedIn(email);
        JsonNode claims = Jwts.payload(b.cookie("access_token"));
        String noKid = Jwts.sign("{\"alg\":\"RS256\",\"typ\":\"JWT\"}", claims.toString(), Jwts.testPrivateKey());
        assertEquals(200, status(browser().bearer(noKid).get("/api/auth/test/me")));
    }

    @Test
    @DisplayName("SSO-T-auth-014 refresh rotates the opaque refresh token and reuse of the old one revokes the family")
    void refreshRotationAndReuse() {
        String email = uniqueEmail("legacy");
        user(email);
        Browser b = loggedIn(email);
        String first = b.cookie("refresh_token");
        MvcResult r = b.post(BASE + "/refresh", null);
        assertEquals(200, status(r));
        String second = b.cookie("refresh_token");
        assertNotEquals(first, second);
        Browser attacker = browser().cookie("refresh_token", first);
        MvcResult reuse = attacker.post(BASE + "/refresh", null);
        assertEquals(400, status(reuse));
        assertTrue(content(reuse).contains("Auth.Errors.Token.Reused"));
        assertEquals(400, status(b.post(BASE + "/refresh", null)));
    }

    @Test
    @DisplayName("SSO-T-auth-015 a stored legacy HS256 refresh token is accepted exactly once and migrated to an opaque family")
    void legacyRefreshMigration() {
        String email = uniqueEmail("legacy");
        UserEntity user = user(email);
        String legacyJwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI" + user.getId() + "In0.legacy" + System.nanoTime();
        refreshTokenRepository.save(RefreshTokenEntity.builder().token(legacyJwt).user(user).deviceId("DEFAULT_DEVICE")
                .activeRole(user.getRoles().iterator().next()).expiryDate(Instant.now().plus(Duration.ofDays(5))).build());
        Browser b = browser().cookie("refresh_token", legacyJwt);
        MvcResult r = b.post(BASE + "/refresh", null);
        assertEquals(200, status(r), content(r));
        assertEquals(43, b.cookie("refresh_token").length());
        assertNotNull(b.cookie("access_token"));
        assertTrue(refreshTokenRepository.findByToken(legacyJwt).isEmpty());
        Browser replay = browser().cookie("refresh_token", legacyJwt);
        assertEquals(400, status(replay.post(BASE + "/refresh", null)));
        assertEquals(200, status(b.post(BASE + "/refresh", null)));
    }

    @Test
    @DisplayName("SSO-T-auth-016 an expired legacy refresh row is rejected and deleted")
    void legacyRefreshExpired() {
        String email = uniqueEmail("legacy");
        UserEntity user = user(email);
        String legacyJwt = "legacy-expired-" + System.nanoTime();
        refreshTokenRepository.save(RefreshTokenEntity.builder().token(legacyJwt).user(user).deviceId("D")
                .activeRole(user.getRoles().iterator().next()).expiryDate(Instant.now().minus(1, ChronoUnit.DAYS)).build());
        MvcResult r = browser().cookie("refresh_token", legacyJwt).post(BASE + "/refresh", null);
        assertEquals(400, status(r));
        assertTrue(content(r).contains("Auth.Errors.Token.Expired"));
        assertTrue(refreshTokenRepository.findByToken(legacyJwt).isEmpty());
    }

    @Test
    @DisplayName("SSO-T-auth-017 legacy BCrypt users log in and are transparently rehashed to PBKDF2-SHA512 with pepper")
    void bcryptRehash() {
        String email = uniqueEmail("legacy");
        bcryptUser(email);
        assertTrue(PasswordService.isBcrypt(userRepository.findByEmail(email).orElseThrow().getPassword()));
        loggedIn(email);
        String stored = userRepository.findByEmail(email).orElseThrow().getPassword();
        assertTrue(PasswordService.isPbkdf2(stored), stored);
        loggedIn(email);
        assertEquals(401, status(login(browser(), email, "wrong-" + PASSWORD)));
    }

    @Test
    @DisplayName("SSO-T-auth-018 wrong password and unknown email answer with the same status and body")
    void uniformFailure() {
        String email = uniqueEmail("legacy");
        user(email);
        MvcResult wrong = login(browser(), email, "nope-" + PASSWORD);
        MvcResult unknown = login(browser(), uniqueEmail("ghost"), PASSWORD);
        assertEquals(401, status(wrong));
        assertEquals(status(wrong), status(unknown));
        assertEquals(content(wrong), content(unknown));
    }

    @Test
    @DisplayName("SSO-T-auth-019 logout revokes the refresh family and clears cookies")
    void logout() {
        String email = uniqueEmail("legacy");
        user(email);
        Browser b = loggedIn(email);
        String refresh = b.cookie("refresh_token");
        assertEquals(200, status(b.post(BASE + "/logout", null)));
        assertNull(b.cookie("access_token"));
        assertEquals(400, status(browser().cookie("refresh_token", refresh).post(BASE + "/refresh", null)));
    }

    @Test
    @DisplayName("SSO-T-auth-020 device revocation removes the refresh binding so the family can no longer refresh")
    void deviceRevocation() {
        String email = uniqueEmail("legacy");
        UserEntity user = user(email);
        Browser b = loggedIn(email);
        deviceSessionService.revokeDevice(user, "DEFAULT_DEVICE");
        assertEquals(400, status(b.post(BASE + "/refresh", null)));
    }

    @Test
    @DisplayName("SSO-T-auth-021 social start path is kept but answers 501 NOT_CONFIGURED without the oauth2 client library")
    void socialPlaceholder() {
        MvcResult r = browser().get("/oauth2/authorization/google");
        assertEquals(501, status(r));
        assertEquals("NOT_CONFIGURED", json(r).get("code").asText());
    }

    @Test
    @DisplayName("SSO-T-auth-022 SSO endpoints are not mapped when naqqa.sso.enabled is false")
    void ssoEndpointsAbsent() {
        assertEquals(404, status(browser().get(BASE + "/methods")));
        assertEquals(404, status(browser().post(BASE + "/otp/request", Map.of("channel", "email", "destination", "a@b.c"))));
    }

    @Test
    @DisplayName("SSO-T-auth-023 security headers are set on auth responses")
    void securityHeaders() {
        MvcResult r = browser().get(BASE + "/test/whoami");
        assertEquals("nosniff", r.getResponse().getHeader("X-Content-Type-Options"));
        assertEquals("DENY", r.getResponse().getHeader("X-Frame-Options"));
        assertEquals("no-referrer", r.getResponse().getHeader("Referrer-Policy"));
        assertTrue(r.getResponse().getHeader("Content-Security-Policy").contains("frame-ancestors 'none'"));
    }

    @Test
    @DisplayName("SSO-T-auth-024 registration stores a PBKDF2 hash and confirm-email creates a user that can log in")
    void registerUsesPbkdf2() {
        String email = uniqueEmail("reg");
        MvcResult r = browser().post(BASE + "/register", Map.of("email", email, "password", PASSWORD, "fullName", "Reg User"));
        assertEquals(200, status(r), content(r));
        String uuid = json(r).get("uuid").asText();
        var reg = registerRecordRepository.findById(uuid).orElseThrow();
        assertTrue(PasswordService.isPbkdf2(reg.getPassword()));
        role("USER", "profile:read");
        MvcResult c = browser().post(BASE + "/confirm-email", Map.of("uuid", uuid, "code", mails.confirmationCode(email)));
        assertEquals(200, status(c), content(c));
        loggedIn(email);
    }

    @Test
    @DisplayName("SSO-T-auth-025 an access token for a disabled-session claim is not required in legacy mode and refresh keeps role")
    void refreshKeepsRole() {
        String email = uniqueEmail("legacy");
        user(email);
        Browser b = loggedIn(email);
        b.post(BASE + "/refresh", null);
        JsonNode claims = Jwts.payload(b.cookie("access_token"));
        assertEquals("USER", claims.get("role").get("name").asText());
        assertFalse(claims.has("sid"));
    }

    @Test
    @DisplayName("SSO-T-auth-560 F-P7 a legacy 2.0 refresh row is claimed atomically once; replay, expiry, unknown, forged HS256 and orphaned rows fail uniformly")
    void legacyRefreshSingleUse() throws Exception {
        String email = uniqueEmail("legacy");
        UserEntity user = user(email);
        String legacyJwt = hs256(Map.of("sub", email), "YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=") + System.nanoTime();
        seedLegacy(user, legacyJwt, Instant.now().plus(Duration.ofDays(20)));
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Browser b = browser().cookie("refresh_token", legacyJwt);
            results.add(pool.submit(() -> {
                start.await();
                return status(b.post(BASE + "/refresh", null));
            }));
        }
        start.countDown();
        int ok = 0;
        for (Future<Integer> f : results) {
            int code = f.get(30, TimeUnit.SECONDS);
            assertTrue(code == 200 || code == 400, Integer.toString(code));
            ok += code == 200 ? 1 : 0;
        }
        pool.shutdownNow();
        assertEquals(1, ok);
        assertTrue(refreshTokenRepository.findByToken(legacyJwt).isEmpty());
        MvcResult replay = browser().cookie("refresh_token", legacyJwt).post(BASE + "/refresh", null);
        assertEquals(400, status(replay));
        assertTrue(content(replay).contains("Auth.Errors.Token.Invalid"), content(replay));

        String forged = hs256(Map.of("sub", email, "exp", Instant.now().plus(Duration.ofDays(30)).getEpochSecond()),
                "YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=");
        MvcResult forgedResult = browser().cookie("refresh_token", forged).post(BASE + "/refresh", null);
        assertEquals(400, status(forgedResult));
        assertNull(cookieValue(forgedResult));

        String orphan = "legacy-orphan-" + System.nanoTime();
        UserEntity gone = user(uniqueEmail("legacy"));
        seedLegacy(gone, orphan, Instant.now().plus(Duration.ofDays(5)));
        userRepository.delete(gone);
        assertEquals(400, status(browser().cookie("refresh_token", orphan).post(BASE + "/refresh", null)));
        assertTrue(refreshTokenRepository.findByToken(orphan).isEmpty());

        int rounds = 15;
        long[] unknown = new long[rounds];
        long[] expired = new long[rounds];
        long[] missingUser = new long[rounds];
        for (int i = 0; i < rounds; i++) {
            String e = "legacy-expired-" + System.nanoTime();
            seedLegacy(user, e, Instant.now().minus(1, ChronoUnit.DAYS));
            String m = "legacy-missing-" + System.nanoTime();
            seedLegacy(gone, m, Instant.now().plus(Duration.ofDays(5)));
            unknown[i] = timed("legacy-unknown-" + System.nanoTime());
            expired[i] = timed(e);
            missingUser[i] = timed(m);
        }
        long a = median(unknown);
        long b = median(expired);
        long c = median(missingUser);
        long min = Math.min(a, Math.min(b, c));
        long max = Math.max(a, Math.max(b, c));
        assertTrue(max <= 3 * min + TimeUnit.MILLISECONDS.toNanos(25), a + " " + b + " " + c);
    }

    private void seedLegacy(UserEntity user, String token, Instant expiry) {
        refreshTokenRepository.save(RefreshTokenEntity.builder().token(token).user(user).deviceId("DEFAULT_DEVICE")
                .activeRole(user.getRoles().iterator().next()).expiryDate(expiry).build());
    }

    private long timed(String token) {
        Browser b = browser().cookie("refresh_token", token);
        long t0 = System.nanoTime();
        MvcResult r = b.post(BASE + "/refresh", null);
        long elapsed = System.nanoTime() - t0;
        assertEquals(400, status(r));
        return elapsed;
    }

    private static long median(long[] values) {
        long[] copy = values.clone();
        Arrays.sort(copy);
        return copy[copy.length / 2];
    }

    private static String cookieValue(MvcResult r) {
        jakarta.servlet.http.Cookie c = r.getResponse().getCookie("access_token");
        return c == null || c.getValue() == null || c.getValue().isEmpty() ? null : c.getValue();
    }

    private static String hs256(Map<String, Object> claims, String base64Key) throws Exception {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        StringBuilder json = new StringBuilder("{");
        for (Map.Entry<String, Object> e : claims.entrySet()) {
            if (json.length() > 1) {
                json.append(',');
            }
            json.append('"').append(e.getKey()).append("\":");
            json.append(e.getValue() instanceof String v ? "\"" + v + "\"" : String.valueOf(e.getValue()));
        }
        json.append('}');
        String signingInput = enc.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + enc.encodeToString(json.toString().getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Base64.getDecoder().decode(base64Key), "HmacSHA256"));
        return signingInput + "." + enc.encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII)));
    }
}
