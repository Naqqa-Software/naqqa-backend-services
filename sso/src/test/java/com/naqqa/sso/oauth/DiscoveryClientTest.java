package com.naqqa.sso.oauth;

import com.naqqa.sso.crypto.MutableClock;
import com.naqqa.sso.json.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.naqqa.sso.oauth.Support.codeOf;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DiscoveryClientTest {
    private MutableClock clock;
    private FakeIdp idp;
    private DiscoveryClient client;
    private final AtomicInteger hits = new AtomicInteger();
    private volatile Map<String, Object> doc;

    @BeforeEach
    void up() throws Exception {
        clock = Support.clock();
        idp = new FakeIdp(clock);
        client = new DiscoveryClient(new SafeHttp(SafeHttp.Options.forTests()), clock);
        doc = valid();
        idp.route("/.well-known/openid-configuration", (ex, b) -> {
            hits.incrementAndGet();
            return FakeIdp.Resp.json(Json.write(doc));
        });
    }

    @AfterEach
    void down() {
        idp.close();
    }

    private Map<String, Object> valid() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("issuer", idp.base);
        d.put("authorization_endpoint", idp.base + "/authorize");
        d.put("token_endpoint", idp.base + "/token");
        d.put("jwks_uri", idp.base + "/jwks");
        d.put("code_challenge_methods_supported", List.of("S256"));
        return d;
    }

    @Test
    @DisplayName("SSO-T-oauth-012 discovery returns endpoints when issuer matches")
    void ok() {
        DiscoveryClient.Document d = client.discover(idp.base);
        assertEquals(idp.base + "/token", d.tokenEndpoint());
        assertEquals(ClientAuth.POST, d.clientAuth());
    }

    @Test
    @DisplayName("SSO-T-oauth-013 issuer in document must equal configured issuer")
    void issuerMismatch() {
        doc.put("issuer", "https://evil.example");
        assertEquals("oauth.discovery_issuer_mismatch", codeOf(() -> client.discover(idp.base)));
    }

    @Test
    @DisplayName("SSO-T-oauth-014 issuer differing only by trailing slash rejected")
    void issuerSlash() {
        doc.put("issuer", idp.base + "/");
        assertEquals("oauth.discovery_issuer_mismatch", codeOf(() -> client.discover(idp.base)));
    }

    @Test
    @DisplayName("SSO-T-oauth-015 missing required endpoint rejected")
    void missing() {
        doc.remove("jwks_uri");
        assertEquals("oauth.discovery_invalid", codeOf(() -> client.discover(idp.base)));
    }

    @Test
    @DisplayName("SSO-T-oauth-016 insecure or private endpoint in document rejected")
    void badEndpoint() {
        doc.put("token_endpoint", "http://idp.example.com/token");
        assertEquals("oauth.url_insecure", codeOf(() -> client.discover(idp.base)));
        doc.put("token_endpoint", "https://10.0.0.9/token");
        client = new DiscoveryClient(new SafeHttp(SafeHttp.Options.forTests()), clock);
        assertEquals("oauth.ssrf_blocked", codeOf(() -> client.discover(idp.base)));
    }

    @Test
    @DisplayName("SSO-T-oauth-017 provider without S256 support rejected")
    void noPkce() {
        doc.put("code_challenge_methods_supported", List.of("plain"));
        assertEquals("oauth.pkce_unsupported", codeOf(() -> client.discover(idp.base)));
    }

    @Test
    @DisplayName("SSO-T-oauth-018 basic client auth selected when post unsupported")
    void basicAuth() {
        doc.put("token_endpoint_auth_methods_supported", List.of("client_secret_basic"));
        assertEquals(ClientAuth.BASIC, client.discover(idp.base).clientAuth());
    }

    @Test
    @DisplayName("SSO-T-oauth-019 discovery is cached for an hour")
    void cached() {
        client.discover(idp.base);
        client.discover(idp.base);
        assertEquals(1, hits.get());
        clock.advance(Duration.ofMinutes(61));
        client.discover(idp.base);
        assertEquals(2, hits.get());
    }

    @Test
    @DisplayName("SSO-T-oauth-020 non-JSON discovery body rejected")
    void garbage() {
        idp.route("/.well-known/openid-configuration", (ex, b) -> FakeIdp.Resp.json("not json"));
        assertEquals("oauth.discovery_invalid", codeOf(() -> client.discover(idp.base)));
    }

    @Test
    @DisplayName("SSO-T-oauth-021 discovery HTTP error gives discovery_failed")
    void httpError() {
        idp.route("/.well-known/openid-configuration", (ex, b) -> FakeIdp.Resp.json(503, "{}"));
        assertEquals("oauth.discovery_failed", codeOf(() -> client.discover(idp.base)));
    }
}
