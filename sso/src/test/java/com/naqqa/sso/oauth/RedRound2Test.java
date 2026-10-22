package com.naqqa.sso.oauth;

import com.naqqa.sso.crypto.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.naqqa.sso.oauth.Support.CLIENT;
import static com.naqqa.sso.oauth.Support.codeOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedRound2Test {
    private MutableClock clock;
    private FakeIdp idp;

    @BeforeEach
    void up() throws Exception {
        clock = Support.clock();
        idp = new FakeIdp(clock);
    }

    @AfterEach
    void down() {
        idp.close();
    }

    private ProviderConfig facebook(String redirect) {
        return ProviderConfig.facebook(CLIENT, "fb-secret", redirect).toBuilder()
                .authorizationEndpoint(idp.base + "/fb/authorize")
                .tokenEndpoint(idp.base + "/token")
                .userinfoEndpoint(idp.base + "/fb/me")
                .debugTokenEndpoint(idp.base + "/fb/debug")
                .build();
    }

    @Test
    @DisplayName("SSO-T-oauth-228 F-R2-1 mix-up: a provider without PKCE cannot share its redirect URI with another provider")
    void sharedRedirectWithoutPkceRejected() {
        ProviderConfig fb = facebook(Support.REDIRECT);
        ProviderConfig evil = Support.generic(idp, "enterprise:evil").build();
        assertEquals("oauth.redirect_uri_shared", codeOf(() -> ProviderRegistry.of(List.of(fb, evil))));
        ProviderConfig fbOwn = facebook("https://app.example/cb/facebook");
        assertNotNull(ProviderRegistry.of(List.of(fbOwn, evil)).find("facebook").orElse(null));
    }

    @Test
    @DisplayName("SSO-T-oauth-229 F-R2-1 mix-up: callback for a provider without PKCE must name the provider, code never reaches a token endpoint otherwise")
    void providerlessCallbackWithoutPkceRejected() {
        OAuthClient client = Support.client(clock, new InMemoryStateStore(clock), facebook("https://app.example/cb/facebook"));
        Support.Started s = Support.start(client, "facebook");
        assertEquals("oauth.provider_required", codeOf(() -> client.complete(Support.cb(s.state(), "victim-code"), s.binding())));
        assertEquals(0, idp.tokenHits.get());
    }

    @Test
    @DisplayName("SSO-T-oauth-230 F-R2-2 email normalization never folds non-ASCII into ASCII (Kelvin sign collision)")
    void unicodeEmailCollision() {
        assertNull(Sanitize.email("Kate@corp.example"));
        assertNull(Sanitize.email("kate@Korp.example"));
        assertNull(Sanitize.email("adımin@corp.example"));
        assertEquals("kate@corp.example", Sanitize.email("Kate@Corp.EXAMPLE"));
    }

    @Test
    @DisplayName("SSO-T-oauth-231 F-R2-5 a slow JWKS endpoint does not block key lookups for other issuers")
    void slowJwksDoesNotBlockOthers() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        idp.route("/slow-jwks", (ex, b) -> {
            entered.countDown();
            Thread.sleep(2000);
            return FakeIdp.Resp.json(idp.jwksBody);
        });
        JwksCache cache = new JwksCache(new SafeHttp(SafeHttp.Options.forTests()), clock);
        String fast = idp.base + "/jwks";
        assertNotNull(cache.find(fast, "k1"));
        CompletableFuture<Object> slow = CompletableFuture.supplyAsync(() -> cache.find(idp.base + "/slow-jwks", "k1"));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        long t0 = System.nanoTime();
        assertNotNull(cache.find(fast, "k1"));
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        assertTrue(ms < 700, "blocked for " + ms + " ms");
        assertNotNull(slow.get(10, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("SSO-T-oauth-232 F-R2-5 a slow discovery document does not block discovery of other issuers")
    void slowDiscoveryDoesNotBlockOthers() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        String slowIssuer = idp.base + "/slow";
        idp.route("/slow/.well-known/openid-configuration", (ex, b) -> {
            entered.countDown();
            Thread.sleep(2000);
            return FakeIdp.Resp.json(404, "{}");
        });
        idp.route("/.well-known/openid-configuration", (ex, b) -> FakeIdp.Resp.json("{\"issuer\":\"" + idp.base
                + "\",\"authorization_endpoint\":\"" + idp.base + "/authorize\",\"token_endpoint\":\"" + idp.base
                + "/token\",\"jwks_uri\":\"" + idp.base + "/jwks\"}"));
        DiscoveryClient discovery = new DiscoveryClient(new SafeHttp(SafeHttp.Options.forTests()), clock);
        assertNotNull(discovery.discover(idp.base));
        CompletableFuture<Object> slow = CompletableFuture.supplyAsync(() -> {
            try {
                return discovery.discover(slowIssuer);
            } catch (RuntimeException e) {
                return e;
            }
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        long t0 = System.nanoTime();
        assertNotNull(discovery.discover(idp.base));
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        assertTrue(ms < 700, "blocked for " + ms + " ms");
        slow.get(10, TimeUnit.SECONDS);
    }
}
