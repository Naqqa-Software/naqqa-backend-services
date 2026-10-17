package com.naqqa.sso.oauth;

import com.naqqa.sso.crypto.MutableClock;
import com.naqqa.sso.json.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.naqqa.sso.oauth.Support.CLIENT;
import static com.naqqa.sso.oauth.Support.codeOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnterpriseAndMiscTest {
    private MutableClock clock;
    private FakeIdp idp;

    @BeforeEach
    void up() throws Exception {
        clock = Support.clock();
        idp = new FakeIdp(clock);
        idp.route("/.well-known/openid-configuration", (ex, b) -> {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("issuer", idp.base);
            d.put("authorization_endpoint", idp.base + "/authorize");
            d.put("token_endpoint", idp.base + "/token");
            d.put("jwks_uri", idp.base + "/jwks");
            return FakeIdp.Resp.json(Json.write(d));
        });
    }

    @AfterEach
    void down() {
        idp.close();
    }

    @Test
    @DisplayName("SSO-T-oauth-022 generic OIDC login through discovery")
    void discoveryLogin() {
        ProviderConfig cfg = ProviderConfig.oidc("corp", idp.base, CLIENT, "s", Support.REDIRECT, null);
        assertTrue(cfg.discovery());
        OAuthClient c = Support.client(clock, new InMemoryStateStore(clock), cfg);
        Support.Started s = Support.start(c, "corp");
        assertTrue(s.begin().authorizationUrl().startsWith(idp.base + "/authorize?"));
        idp.respondTokens(idp.sign(idp.idClaims(CLIENT, "e1", s.nonce())), "at");
        assertEquals("e1", c.complete(Support.cb(s.state(), "c"), s.binding()).subject());
    }

    @Test
    @DisplayName("SSO-T-oauth-023 discovery with a different issuer in the document aborts begin")
    void discoveryIssuerMismatch() {
        idp.route("/.well-known/openid-configuration", (ex, b) -> FakeIdp.Resp.json("{\"issuer\":\"https://evil.example\",\"authorization_endpoint\":\"" + idp.base + "/authorize\",\"token_endpoint\":\"" + idp.base + "/token\",\"jwks_uri\":\"" + idp.base + "/jwks\"}"));
        OAuthClient c = Support.client(clock, new InMemoryStateStore(clock), ProviderConfig.oidc("corp", idp.base, CLIENT, "s", Support.REDIRECT, null));
        assertEquals("oauth.discovery_issuer_mismatch", codeOf(() -> c.begin("corp", "/")));
    }

    @Test
    @DisplayName("SSO-T-oauth-024 two enterprise tenants are isolated by state and provider id")
    void tenants() {
        ProviderConfig a = ProviderConfig.enterprise("acme", idp.base, CLIENT, "s", Support.REDIRECT, Set.of("acme.example"));
        ProviderConfig b = ProviderConfig.enterprise("globex", idp.base, CLIENT, "s", Support.REDIRECT, Set.of("globex.example"));
        OAuthClient c = Support.client(clock, new InMemoryStateStore(clock), a, b);
        Support.Started sa = Support.start(c, "enterprise:acme");
        Support.Started sb = Support.start(c, "enterprise:globex");
        idp.respondTokens(idp.sign(idp.idClaims(CLIENT, "x", sa.nonce())), "at");
        ExternalIdentity ia = c.complete("enterprise:acme", Support.cb(sa.state(), "c"), sa.binding());
        assertEquals("enterprise:acme", ia.provider());
        idp.respondTokens(idp.sign(idp.idClaims(CLIENT, "x", sb.nonce())), "at");
        assertEquals("oauth.provider_mismatch", codeOf(() -> c.complete("enterprise:acme", Support.cb(sb.state(), "c"), sb.binding())));
    }

    @Test
    @DisplayName("SSO-T-oauth-025 duplicate provider ids rejected in registry")
    void duplicateProviders() {
        ProviderConfig a = ProviderConfig.oidc("dup", idp.base, CLIENT, "s", Support.REDIRECT, null);
        assertEquals("oauth.provider_duplicate", codeOf(() -> ProviderRegistry.of(List.of(a, a))));
    }

    @Test
    @DisplayName("SSO-T-oauth-236 F-E18 the Facebook debug_token endpoint defaults to the pinned Graph version and can be overridden")
    void facebookDebugEndpoint() {
        ProviderConfig fb = ProviderConfig.facebook("cid", "s", Support.REDIRECT);
        assertEquals("https://graph.facebook.com/v19.0/debug_token", fb.debugTokenEndpoint());
        assertEquals("http://127.0.0.1:1/d", fb.toBuilder().debugTokenEndpoint("http://127.0.0.1:1/d").build().debugTokenEndpoint());
    }

    @Test
    @DisplayName("SSO-T-oauth-026 provider config toString does not expose the client secret")
    void noSecretInToString() {
        ProviderConfig cfg = ProviderConfig.google("cid", "super-secret-value", Support.REDIRECT);
        assertFalse(cfg.toString().contains("super-secret-value"));
        assertFalse(ProviderConfig.facebook("cid", "fb-super-secret", Support.REDIRECT).toString().contains("fb-super-secret"));
    }

    @Test
    @DisplayName("SSO-T-oauth-027 Google preset values")
    void googlePreset() {
        ProviderConfig g = ProviderConfig.google("cid", "s", Support.REDIRECT);
        assertEquals("https://accounts.google.com", g.issuer());
        assertTrue(g.pkce());
        assertTrue(g.scopes().contains("openid"));
        assertEquals(ProviderKind.GOOGLE, g.kind());
    }

    @Test
    @DisplayName("SSO-T-oauth-028 invalid provider config rejected")
    void invalidConfig() {
        assertEquals("oauth.config_invalid", codeOf(() -> ProviderConfig.builder("bad id!", ProviderKind.GENERIC_OIDC).issuer("i").clientId("c").redirectUri("r").build()));
        assertEquals("oauth.config_invalid", codeOf(() -> ProviderConfig.builder("x", ProviderKind.GITHUB).clientId("c").redirectUri("r")
                .authorizationEndpoint("a").tokenEndpoint("t").build()));
        assertEquals("oauth.config_invalid", codeOf(() -> ProviderConfig.builder("x", ProviderKind.GENERIC_OIDC).clientId("c").redirectUri("r")
                .authorizationEndpoint("a").tokenEndpoint("t").jwksUri("j").build()));
        assertEquals("oauth.config_invalid", codeOf(() -> ProviderConfig.builder("x", ProviderKind.GENERIC_OIDC).issuer("i").clientId("c").redirectUri("r").responseMode("fragment").build()));
    }

    @Test
    @DisplayName("SSO-T-oauth-029 directory connector stub refuses every operation")
    void directoryStub() {
        DirectoryConnector d = new UnsupportedDirectoryConnector();
        assertEquals("oauth.directory_unsupported", codeOf(() -> d.bind("u", "p".toCharArray())));
        assertEquals("oauth.directory_unsupported", codeOf(() -> d.search(new DirectoryConnector.SearchQuery("dc=x", "uid", "u", List.of("mail"), 10))));
    }

    @Test
    @DisplayName("SSO-T-oauth-030 in-memory state store refuses to grow without bound")
    void storeBound() {
        InMemoryStateStore store = new InMemoryStateStore(clock);
        java.time.Instant exp = clock.instant().plusSeconds(600);
        for (int i = 0; i < InMemoryStateStore.MAX_ENTRIES; i++) {
            store.put(new StateRecord("h" + i, "p", "n", null, "b", "/", exp));
        }
        assertEquals("oauth.state_store_full", codeOf(() -> store.put(new StateRecord("extra", "p", "n", null, "b", "/", exp))));
        clock.advance(java.time.Duration.ofMinutes(11));
        store.put(new StateRecord("extra", "p", "n", null, "b", "/", clock.instant().plusSeconds(600)));
        assertEquals(1, store.size());
    }

    @Test
    @DisplayName("SSO-T-oauth-031 ExternalIdentity forces emailVerified false without an email")
    void identityNoEmail() {
        ExternalIdentity id = new ExternalIdentity("p", "s", null, true, null, null, Map.of());
        assertFalse(id.emailVerified());
    }
}
