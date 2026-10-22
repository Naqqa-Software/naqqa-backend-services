package com.naqqa.sso.oauth;

import com.naqqa.sso.crypto.MutableClock;
import com.naqqa.sso.crypto.SigningAlgorithm;
import com.naqqa.sso.crypto.SigningKey;
import com.naqqa.sso.jose.Jwt;
import com.naqqa.sso.jose.JwtClaims;
import com.naqqa.sso.jose.JwtValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.Duration;
import java.util.Map;

import static com.naqqa.sso.oauth.Support.CLIENT;
import static com.naqqa.sso.oauth.Support.codeOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppleProviderTest {
    private MutableClock clock;
    private FakeIdp idp;
    private SigningKey teamKey;
    private AppleClientSecret secret;
    private OAuthClient client;

    @BeforeEach
    void up() throws Exception {
        clock = Support.clock();
        idp = new FakeIdp(clock);
        teamKey = SigningKey.generate(SigningAlgorithm.ES256);
        secret = new AppleClientSecret("TEAM123456", "KEY1234567", CLIENT, teamKey, clock, Duration.ofDays(30));
        ProviderConfig cfg = ProviderConfig.apple(CLIENT, secret, Support.REDIRECT).toBuilder()
                .issuer(idp.base)
                .authorizationEndpoint(idp.base + "/auth/authorize")
                .tokenEndpoint(idp.base + "/token")
                .jwksUri(idp.base + "/jwks")
                .build();
        client = Support.client(clock, new InMemoryStateStore(clock), cfg);
    }

    @AfterEach
    void down() {
        idp.close();
    }

    @Test
    @DisplayName("SSO-T-oauth-001 Apple client secret is an ES256 JWT with team, client, audience and expiry")
    void secretClaims() {
        String jwt = secret.secret();
        JwtClaims claims = Jwt.verify(jwt, teamKey.publicOnly().withKid("KEY1234567"),
                JwtValidator.builder(clock).issuer("TEAM123456").audience("https://appleid.apple.com").build()).claims();
        assertEquals(CLIENT, claims.subject());
        assertEquals(clock.instant().plus(Duration.ofDays(30)).getEpochSecond(), claims.expiresAt().getEpochSecond());
        Map<String, Object> header = com.naqqa.sso.jose.Jws.peekHeader(jwt);
        assertEquals("ES256", header.get("alg"));
        assertEquals("KEY1234567", header.get("kid"));
    }

    @Test
    @DisplayName("SSO-T-oauth-002 Apple client secret lifetime above 6 months rejected, exactly 6 months accepted")
    void secretLifetime() {
        assertEquals("oauth.apple_secret_lifetime",
                codeOf(() -> new AppleClientSecret("T", "K", CLIENT, teamKey, clock, Duration.ofSeconds(15777001L))));
        assertEquals("oauth.apple_secret_lifetime",
                codeOf(() -> new AppleClientSecret("T", "K", CLIENT, teamKey, clock, Duration.ZERO)));
        new AppleClientSecret("T", "K", CLIENT, teamKey, clock, Duration.ofSeconds(15777000L)).secret();
    }

    @Test
    @DisplayName("SSO-T-oauth-003 Apple client secret requires an ES256 private key")
    void secretKeyType() {
        assertEquals("oauth.apple_secret_invalid",
                codeOf(() -> new AppleClientSecret("T", "K", CLIENT, SigningKey.generate(SigningAlgorithm.RS256), clock, Duration.ofDays(1))));
        assertEquals("oauth.apple_secret_invalid",
                codeOf(() -> new AppleClientSecret("T", "K", CLIENT, teamKey.publicOnly(), clock, Duration.ofDays(1))));
    }

    @Test
    @DisplayName("SSO-T-oauth-004 Apple client secret toString never leaks the token")
    void secretToString() {
        assertFalse(secret.toString().contains("eyJ"));
    }

    @Test
    @DisplayName("SSO-T-oauth-005 Apple authorization URL uses form_post and name email scopes")
    void authorizationUrl() throws Exception {
        SafeHttp http = new SafeHttp(SafeHttp.Options.forTests().withResolver(h -> new InetAddress[] {InetAddress.getByAddress(new byte[] {17, 0, 0, 1})}));
        OAuthClient c = new OAuthClient(ProviderRegistry.of(java.util.List.of(ProviderConfig.apple(CLIENT, secret, Support.REDIRECT))),
                new InMemoryStateStore(clock), http, clock);
        OAuthClient.Begin b = c.begin("apple", "/");
        assertTrue(b.authorizationUrl().startsWith("https://appleid.apple.com/auth/authorize?"));
        Map<String, String> q = Support.query(b.authorizationUrl());
        assertEquals("form_post", q.get("response_mode"));
        assertEquals("openid name email", q.get("scope"));
    }

    @Test
    @DisplayName("SSO-T-oauth-006 Apple token request sends the generated JWT as client_secret")
    void secretSent() {
        Support.Started s = Support.start(client, "apple");
        idp.respondTokens(idp.sign(idp.idClaims(CLIENT, "apple-sub", s.nonce())), "at");
        client.complete(Support.cb(s.state(), "c"), s.binding());
        String sent = idp.lastForm.get("client_secret");
        assertEquals(3, sent.split("\\.").length);
        Jwt.verify(sent, teamKey.publicOnly().withKid("KEY1234567"),
                JwtValidator.builder(clock).issuer("TEAM123456").audience("https://appleid.apple.com").build());
    }

    @Test
    @DisplayName("SSO-T-oauth-007 Apple name taken from the user form field on first login")
    void nameFirstLogin() {
        Support.Started s = Support.start(client, "apple");
        Map<String, Object> claims = idp.idClaims(CLIENT, "apple-sub", s.nonce());
        claims.remove("name");
        idp.respondTokens(idp.sign(claims), "at");
        Map<String, String> cb = new java.util.LinkedHashMap<>();
        cb.put("state", s.state());
        cb.put("code", "c");
        cb.put("user", "{\"name\":{\"firstName\":\"Ada\",\"lastName\":\"Lovelace\"},\"email\":\"attacker@evil.example\"}");
        ExternalIdentity id = client.complete(CallbackParams.of(cb), s.binding());
        assertEquals("Ada Lovelace", id.name());
        assertEquals("user@example.com", id.email());
    }

    @Test
    @DisplayName("SSO-T-oauth-008 Apple user field email is never trusted")
    void userFieldEmailIgnored() {
        Support.Started s = Support.start(client, "apple");
        Map<String, Object> claims = idp.idClaims(CLIENT, "apple-sub", s.nonce());
        claims.remove("name");
        claims.remove("email");
        claims.remove("email_verified");
        idp.respondTokens(idp.sign(claims), "at");
        Map<String, String> cb = new java.util.LinkedHashMap<>();
        cb.put("state", s.state());
        cb.put("code", "c");
        cb.put("user", "{\"name\":{\"firstName\":\"Eve\"},\"email\":\"victim@corp.example\"}");
        ExternalIdentity id = client.complete(CallbackParams.of(cb), s.binding());
        assertNull(id.email());
        assertFalse(id.emailVerified());
    }

    @Test
    @DisplayName("SSO-T-oauth-009 Apple login without email yields null email and unverified")
    void missingEmail() {
        Support.Started s = Support.start(client, "apple");
        Map<String, Object> claims = idp.idClaims(CLIENT, "apple-sub", s.nonce());
        claims.remove("email");
        claims.remove("email_verified");
        claims.remove("name");
        idp.respondTokens(idp.sign(claims), "at");
        ExternalIdentity id = client.complete(Support.cb(s.state(), "c"), s.binding());
        assertNull(id.email());
        assertFalse(id.emailVerified());
        assertNull(id.name());
        assertEquals("apple-sub", id.subject());
    }

    @Test
    @DisplayName("SSO-T-oauth-010 Apple email_verified string true and private relay flag handled")
    void stringBooleans() {
        Support.Started s = Support.start(client, "apple");
        Map<String, Object> claims = idp.idClaims(CLIENT, "apple-sub", s.nonce());
        claims.put("email", "abc123@privaterelay.appleid.com");
        claims.put("email_verified", "true");
        claims.put("is_private_email", "true");
        idp.respondTokens(idp.sign(claims), "at");
        ExternalIdentity id = client.complete(Support.cb(s.state(), "c"), s.binding());
        assertTrue(id.emailVerified());
        assertEquals("true", id.claims().get("is_private_email"));
    }

    @Test
    @DisplayName("SSO-T-oauth-011 Apple malformed user field ignored")
    void badUserField() {
        Support.Started s = Support.start(client, "apple");
        Map<String, Object> claims = idp.idClaims(CLIENT, "apple-sub", s.nonce());
        claims.remove("name");
        idp.respondTokens(idp.sign(claims), "at");
        Map<String, String> cb = new java.util.LinkedHashMap<>();
        cb.put("state", s.state());
        cb.put("code", "c");
        cb.put("user", "{{{{");
        assertNull(client.complete(CallbackParams.of(cb), s.binding()).name());
    }
}
