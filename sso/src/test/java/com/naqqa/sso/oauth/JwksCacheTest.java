package com.naqqa.sso.oauth;

import com.naqqa.sso.crypto.MutableClock;
import com.naqqa.sso.crypto.SigningAlgorithm;
import com.naqqa.sso.crypto.SigningKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.naqqa.sso.oauth.Support.codeOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JwksCacheTest {
    private MutableClock clock;
    private FakeIdp idp;
    private JwksCache cache;
    private String uri;

    @BeforeEach
    void up() throws Exception {
        clock = Support.clock();
        idp = new FakeIdp(clock);
        cache = new JwksCache(new SafeHttp(SafeHttp.Options.forTests()), clock);
        uri = idp.base + "/jwks";
    }

    @AfterEach
    void down() {
        idp.close();
    }

    @Test
    @DisplayName("SSO-T-oauth-094 keys are cached within the TTL")
    void cached() {
        assertNotNull(cache.find(uri, "k1"));
        assertNotNull(cache.find(uri, "k1"));
        assertEquals(1, idp.jwksHits.get());
    }

    @Test
    @DisplayName("SSO-T-oauth-095 Cache-Control max-age is respected")
    void maxAge() {
        idp.jwksCacheControl = "public, max-age=1800";
        cache.find(uri, "k1");
        clock.advance(Duration.ofSeconds(1799));
        cache.find(uri, "k1");
        assertEquals(1, idp.jwksHits.get());
        clock.advance(Duration.ofSeconds(2));
        cache.find(uri, "k1");
        assertEquals(2, idp.jwksHits.get());
    }

    @Test
    @DisplayName("SSO-T-oauth-096 tiny max-age is raised to 5 minutes")
    void minBound() {
        idp.jwksCacheControl = "max-age=1";
        cache.find(uri, "k1");
        clock.advance(Duration.ofSeconds(299));
        cache.find(uri, "k1");
        assertEquals(1, idp.jwksHits.get());
        clock.advance(Duration.ofSeconds(2));
        cache.find(uri, "k1");
        assertEquals(2, idp.jwksHits.get());
    }

    @Test
    @DisplayName("SSO-T-oauth-097 huge max-age is capped at 24 hours")
    void maxBound() {
        idp.jwksCacheControl = "max-age=999999999";
        cache.find(uri, "k1");
        clock.advance(Duration.ofHours(23));
        cache.find(uri, "k1");
        assertEquals(1, idp.jwksHits.get());
        clock.advance(Duration.ofHours(1).plusSeconds(1));
        cache.find(uri, "k1");
        assertEquals(2, idp.jwksHits.get());
    }

    @Test
    @DisplayName("SSO-T-oauth-098 TTL parsing: no-store, missing and garbage headers")
    void ttlParsing() {
        assertEquals(JwksCache.MIN_TTL, JwksCache.ttl("no-store"));
        assertEquals(JwksCache.MIN_TTL, JwksCache.ttl("no-cache, max-age=9000"));
        assertEquals(JwksCache.DEFAULT_TTL, JwksCache.ttl(null));
        assertEquals(JwksCache.DEFAULT_TTL, JwksCache.ttl("max-age=abc"));
        assertEquals(Duration.ofSeconds(600), JwksCache.ttl("MAX-AGE=600"));
        assertEquals(JwksCache.MIN_TTL, JwksCache.ttl("max-age=-5"));
    }

    @Test
    @DisplayName("SSO-T-oauth-099 unknown kid refetches at most once per minute")
    void kidFlood() {
        cache.find(uri, "k1");
        for (int i = 0; i < 100; i++) {
            int n = i;
            assertEquals("oauth.jwks_kid_unknown", codeOf(() -> cache.find(uri, "bogus-" + n)));
        }
        assertEquals(1, idp.jwksHits.get());
        clock.advance(Duration.ofSeconds(61));
        for (int i = 0; i < 100; i++) {
            int n = i;
            assertEquals("oauth.jwks_kid_unknown", codeOf(() -> cache.find(uri, "other-" + n)));
        }
        assertEquals(2, idp.jwksHits.get());
    }

    @Test
    @DisplayName("SSO-T-oauth-100 key rotation picked up after the refetch interval")
    void rotation() {
        cache.find(uri, "k1");
        SigningKey k2 = SigningKey.generate(SigningAlgorithm.ES256).withKid("k2");
        idp.jwksBody = FakeIdp.jwks(idp.key, k2);
        assertEquals("oauth.jwks_kid_unknown", codeOf(() -> cache.find(uri, "k2")));
        clock.advance(Duration.ofSeconds(61));
        assertEquals("k2", cache.find(uri, "k2").kid());
    }

    @Test
    @DisplayName("SSO-T-oauth-101 token without kid works with a single-key set only")
    void noKid() {
        assertEquals("k1", cache.find(uri, null).kid());
        SigningKey k2 = SigningKey.generate(SigningAlgorithm.ES256).withKid("k2");
        idp.jwksBody = FakeIdp.jwks(idp.key, k2);
        cache.invalidate(uri);
        assertEquals("oauth.jwks_kid_unknown", codeOf(() -> cache.find(uri, null)));
    }

    @Test
    @DisplayName("SSO-T-oauth-102 malformed JWKS rejected")
    void malformed() {
        idp.jwksBody = "{\"nope\":1}";
        assertEquals("jwks.invalid", codeOf(() -> cache.find(uri, "k1")));
    }

    @Test
    @DisplayName("SSO-T-oauth-103 JWKS with private key material rejected")
    void privateMaterial() {
        idp.jwksBody = "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"k1\",\"n\":\"AQAB\",\"e\":\"AQAB\",\"d\":\"AQAB\"}]}";
        assertEquals("jwks.private_material", codeOf(() -> cache.find(uri, "k1")));
    }

    @Test
    @DisplayName("SSO-T-oauth-104 failing JWKS endpoint is not hammered")
    void failureBackoff() {
        idp.route("/jwks", (ex, b) -> {
            idp.jwksHits.incrementAndGet();
            return FakeIdp.Resp.json(500, "{}");
        });
        for (int i = 0; i < 5; i++) {
            codeOf(() -> cache.find(uri, "k1"));
        }
        assertEquals(1, idp.jwksHits.get());
    }

    @Test
    @DisplayName("SSO-T-oauth-105 JWKS non-JSON content type rejected")
    void jwksContentType() {
        idp.route("/jwks", (ex, b) -> new FakeIdp.Resp(200, "text/html", "<x/>".getBytes(), java.util.Map.of()));
        assertEquals("oauth.content_type_invalid", codeOf(() -> cache.find(uri, "k1")));
    }

    @Test
    @DisplayName("SSO-T-oauth-106 JWKS URI pointing at private address never fetched")
    void jwksSsrf() {
        assertEquals("oauth.ssrf_blocked", codeOf(() -> cache.find("https://169.254.169.254/keys", "k1")));
        assertEquals("oauth.ssrf_blocked", codeOf(() -> cache.find("https://10.0.0.1/keys", "k1")));
    }
}
