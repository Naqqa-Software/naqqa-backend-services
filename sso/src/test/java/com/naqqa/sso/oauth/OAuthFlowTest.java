package com.naqqa.sso.oauth;

import com.naqqa.sso.crypto.MutableClock;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.naqqa.sso.oauth.Support.CLIENT;
import static com.naqqa.sso.oauth.Support.codeOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OAuthFlowTest {
    private MutableClock clock;
    private FakeIdp idp;
    private InMemoryStateStore store;
    private OAuthClient client;

    @BeforeEach
    void up() throws Exception {
        clock = Support.clock();
        idp = new FakeIdp(clock);
        store = new InMemoryStateStore(clock);
        client = Support.client(clock, store, Support.generic(idp, "fake").build());
    }

    @AfterEach
    void down() {
        idp.close();
    }

    private void issue(Support.Started s, String sub) {
        idp.respondTokens(idp.sign(idp.idClaims(CLIENT, sub, s.nonce())), "at-1");
    }

    @Test
    @DisplayName("SSO-T-oauth-133 begin builds an authorization URL with state, nonce and PKCE S256")
    void beginUrl() {
        Support.Started s = Support.start(client, "fake");
        assertEquals("code", s.query().get("response_type"));
        assertEquals(CLIENT, s.query().get("client_id"));
        assertEquals(Support.REDIRECT, s.query().get("redirect_uri"));
        assertEquals("openid email profile", s.query().get("scope"));
        assertEquals("S256", s.query().get("code_challenge_method"));
        assertEquals(43, s.query().get("code_challenge").length());
        assertEquals(43, s.state().length());
        assertEquals(43, s.nonce().length());
        assertTrue(s.begin().authorizationUrl().startsWith(idp.base + "/authorize?"));
    }

    @Test
    @DisplayName("SSO-T-oauth-134 state carries 32 bytes of entropy and is unique per call")
    void stateEntropy() {
        Support.Started a = Support.start(client, "fake");
        Support.Started b = Support.start(client, "fake");
        assertEquals(32, Bytes.b64urlDecode(a.state()).length);
        assertNotEquals(a.state(), b.state());
        assertNotEquals(a.binding(), b.binding());
        assertNotEquals(a.nonce(), b.nonce());
    }

    @Test
    @DisplayName("SSO-T-oauth-135 state is stored hashed, never raw")
    void stateHashed() {
        Support.Started s = Support.start(client, "fake");
        assertTrue(store.consume(s.state()).isEmpty());
        StateRecord r = store.consume(OAuthClient.hash(s.state())).orElseThrow();
        assertEquals(OAuthClient.hash(s.binding()), r.bindingHash());
        assertNotEquals(s.binding(), r.bindingHash());
        assertFalse(r.toString().contains(s.state()));
    }

    @Test
    @DisplayName("SSO-T-oauth-136 full login returns the external identity")
    void happy() {
        Support.Started s = Support.start(client, "fake");
        issue(s, "sub-42");
        OAuthClient.Completion c = client.completeWithRedirect("fake", Support.cb(s.state(), "code-1"), s.binding());
        ExternalIdentity id = c.identity();
        assertEquals("fake", id.provider());
        assertEquals("sub-42", id.subject());
        assertEquals("user@example.com", id.email());
        assertTrue(id.emailVerified());
        assertEquals("Test User", id.name());
        assertEquals("/after", c.redirectAfter());
        assertEquals("sub-42", id.claims().get("sub"));
    }

    @Test
    @DisplayName("SSO-T-oauth-137 token request carries code, verifier matching the challenge, redirect_uri and secret")
    void tokenRequest() {
        Support.Started s = Support.start(client, "fake");
        issue(s, "u");
        client.complete(Support.cb(s.state(), "code-xyz"), s.binding());
        Map<String, String> f = idp.lastForm;
        assertEquals("authorization_code", f.get("grant_type"));
        assertEquals("code-xyz", f.get("code"));
        assertEquals(Support.REDIRECT, f.get("redirect_uri"));
        assertEquals(CLIENT, f.get("client_id"));
        assertEquals("s3cret-value", f.get("client_secret"));
        String expected = Bytes.b64url(Hashes.sha256(Bytes.utf8(f.get("code_verifier"))));
        assertEquals(s.query().get("code_challenge"), expected);
    }

    @Test
    @DisplayName("SSO-T-oauth-138 complete without provider id uses the provider stored with the state")
    void providerFromState() {
        Support.Started s = Support.start(client, "fake");
        issue(s, "u");
        assertEquals("fake", client.complete(Support.cb(s.state(), "c"), s.binding()).provider());
    }

    @Test
    @DisplayName("SSO-T-oauth-139 callback arriving for a different provider than started is rejected")
    void providerMismatch() {
        Support.Started s = Support.start(client, "fake");
        issue(s, "u");
        assertEquals("oauth.provider_mismatch", codeOf(() -> client.complete("other", Support.cb(s.state(), "c"), s.binding())));
        assertEquals(0, idp.tokenHits.get());
    }

    @Test
    @DisplayName("SSO-T-oauth-140 state missing rejected before any network call")
    void stateMissing() {
        assertEquals("oauth.state_missing", codeOf(() -> client.complete(Support.cb(null, "c"), "b")));
        assertEquals("oauth.state_missing", codeOf(() -> client.complete(Support.cb("", "c"), "b")));
        assertEquals("oauth.state_missing", codeOf(() -> client.complete(Support.cb("x".repeat(300), "c"), "b")));
        assertEquals(0, idp.tokenHits.get());
    }

    @Test
    @DisplayName("SSO-T-oauth-141 unknown state rejected")
    void stateUnknown() {
        Support.Started s = Support.start(client, "fake");
        assertEquals("oauth.state_invalid", codeOf(() -> client.complete(Support.cb(Bytes.randomToken(32), "c"), s.binding())));
        assertEquals(0, idp.tokenHits.get());
    }

    @Test
    @DisplayName("SSO-T-oauth-142 state mismatch between two flows rejected and the real state survives")
    void stateMismatch() {
        Support.Started a = Support.start(client, "fake");
        Support.Started b = Support.start(client, "fake");
        issue(a, "u");
        assertEquals("oauth.binding_mismatch", codeOf(() -> client.complete(Support.cb(a.state(), "c"), b.binding())));
        issue(b, "u");
        assertEquals("u", client.complete(Support.cb(b.state(), "c"), b.binding()).subject());
    }

    @Test
    @DisplayName("SSO-T-oauth-143 state is single use, replay rejected")
    void stateReplay() {
        Support.Started s = Support.start(client, "fake");
        issue(s, "u");
        client.complete(Support.cb(s.state(), "c"), s.binding());
        assertEquals("oauth.state_invalid", codeOf(() -> client.complete(Support.cb(s.state(), "c"), s.binding())));
        assertEquals(1, idp.tokenHits.get());
    }

    @Test
    @DisplayName("SSO-T-oauth-144 state is consumed even when the exchange later fails")
    void stateConsumedOnFailure() {
        Support.Started s = Support.start(client, "fake");
        idp.tokenStatus = 400;
        codeOf(() -> client.complete(Support.cb(s.state(), "c"), s.binding()));
        idp.tokenStatus = 200;
        issue(s, "u");
        assertEquals("oauth.state_invalid", codeOf(() -> client.complete(Support.cb(s.state(), "c"), s.binding())));
    }

    @Test
    @DisplayName("SSO-T-oauth-145 state expires after 10 minutes")
    void stateExpiry() {
        Support.Started s = Support.start(client, "fake");
        issue(s, "u");
        clock.advance(Duration.ofMinutes(10));
        assertEquals("oauth.state_expired", codeOf(() -> client.complete(Support.cb(s.state(), "c"), s.binding())));
    }

    @Test
    @DisplayName("SSO-T-oauth-146 state valid just before 10 minutes")
    void stateJustBeforeExpiry() {
        Support.Started s = Support.start(client, "fake");
        clock.advance(Duration.ofMinutes(10).minusSeconds(1));
        issue(s, "u");
        assertEquals("u", client.complete(Support.cb(s.state(), "c"), s.binding()).subject());
    }

    @Test
    @DisplayName("SSO-T-oauth-147 browser binding required and checked")
    void binding() {
        Support.Started s = Support.start(client, "fake");
        issue(s, "u");
        assertEquals("oauth.binding_missing", codeOf(() -> client.complete(Support.cb(s.state(), "c"), null)));
        Support.Started t = Support.start(client, "fake");
        assertEquals("oauth.binding_mismatch", codeOf(() -> client.complete(Support.cb(t.state(), "c"), Bytes.randomToken(32))));
    }

    @Test
    @DisplayName("SSO-T-oauth-148 provider error access_denied maps to a stable code")
    void providerError() {
        Support.Started s = Support.start(client, "fake");
        Map<String, String> m = new LinkedHashMap<>();
        m.put("state", s.state());
        m.put("error", "access_denied");
        assertEquals("oauth.provider_error.access_denied", codeOf(() -> client.complete(CallbackParams.of(m), s.binding())));
    }

    @Test
    @DisplayName("SSO-T-oauth-149 unknown provider error collapses to a generic code and is not reflected")
    void providerErrorUnknown() {
        Support.Started s = Support.start(client, "fake");
        Map<String, String> m = new LinkedHashMap<>();
        m.put("state", s.state());
        m.put("error", "<script>alert(1)</script>");
        m.put("error_description", "<img src=x onerror=alert(2)>");
        com.naqqa.sso.SsoException e = org.junit.jupiter.api.Assertions.assertThrows(com.naqqa.sso.SsoException.class,
                () -> client.complete(CallbackParams.of(m), s.binding()));
        assertEquals("oauth.provider_error", e.code());
        assertFalse(e.getMessage().contains("script"));
        assertFalse(e.getMessage().contains("onerror"));
    }

    @Test
    @DisplayName("SSO-T-oauth-150 provider error still consumes the state")
    void providerErrorConsumes() {
        Support.Started s = Support.start(client, "fake");
        Map<String, String> m = new LinkedHashMap<>();
        m.put("state", s.state());
        m.put("error", "server_error");
        codeOf(() -> client.complete(CallbackParams.of(m), s.binding()));
        assertEquals("oauth.state_invalid", codeOf(() -> client.complete(CallbackParams.of(m), s.binding())));
    }

    @Test
    @DisplayName("SSO-T-oauth-151 provider error without a valid state is a state error, not provider text")
    void providerErrorNoState() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("error", "access_denied");
        assertEquals("oauth.state_missing", codeOf(() -> client.complete(CallbackParams.of(m), "b")));
    }

    @Test
    @DisplayName("SSO-T-oauth-152 missing code rejected")
    void codeMissing() {
        Support.Started s = Support.start(client, "fake");
        assertEquals("oauth.code_missing", codeOf(() -> client.complete(Support.cb(s.state(), null), s.binding())));
    }

    @Test
    @DisplayName("SSO-T-oauth-153 oversized code rejected")
    void codeTooLong() {
        Support.Started s = Support.start(client, "fake");
        assertEquals("oauth.code_invalid", codeOf(() -> client.complete(Support.cb(s.state(), "c".repeat(5000)), s.binding())));
    }

    @Test
    @DisplayName("SSO-T-oauth-154 issuer mix-up: iss callback parameter from another IdP rejected (RFC 9207)")
    void issuerMixUp() {
        Support.Started s = Support.start(client, "fake");
        issue(s, "u");
        Map<String, String> m = new LinkedHashMap<>();
        m.put("state", s.state());
        m.put("code", "c");
        m.put("iss", "https://evil.example");
        assertEquals("oauth.issuer_mismatch", codeOf(() -> client.complete(CallbackParams.of(m), s.binding())));
        assertEquals(0, idp.tokenHits.get());
    }

    @Test
    @DisplayName("SSO-T-oauth-155 matching iss callback parameter accepted")
    void issuerParamOk() {
        Support.Started s = Support.start(client, "fake");
        issue(s, "u");
        Map<String, String> m = new LinkedHashMap<>();
        m.put("state", s.state());
        m.put("code", "c");
        m.put("iss", idp.base);
        assertEquals("u", client.complete(CallbackParams.of(m), s.binding()).subject());
    }

    @Test
    @DisplayName("SSO-T-oauth-156 token endpoint error status gives stable code without body")
    void tokenError() {
        Support.Started s = Support.start(client, "fake");
        idp.tokenStatus = 400;
        idp.tokenResponse = "{\"error\":\"invalid_grant\",\"error_description\":\"secret-detail\"}";
        com.naqqa.sso.SsoException e = org.junit.jupiter.api.Assertions.assertThrows(com.naqqa.sso.SsoException.class,
                () -> client.complete(Support.cb(s.state(), "c"), s.binding()));
        assertEquals("oauth.token_exchange_failed", e.code());
        assertFalse(String.valueOf(e.getMessage()).contains("secret-detail"));
    }

    @Test
    @DisplayName("SSO-T-oauth-157 token endpoint returning HTML rejected")
    void tokenHtml() {
        Support.Started s = Support.start(client, "fake");
        idp.tokenContentType = "text/html";
        idp.tokenResponse = "<html></html>";
        assertEquals("oauth.content_type_invalid", codeOf(() -> client.complete(Support.cb(s.state(), "c"), s.binding())));
    }

    @Test
    @DisplayName("SSO-T-oauth-158 token endpoint returning invalid JSON rejected")
    void tokenGarbage() {
        Support.Started s = Support.start(client, "fake");
        idp.tokenResponse = "{not json";
        assertEquals("oauth.token_response_invalid", codeOf(() -> client.complete(Support.cb(s.state(), "c"), s.binding())));
    }

    @Test
    @DisplayName("SSO-T-oauth-159 missing id_token for an OIDC provider rejected")
    void idTokenMissing() {
        Support.Started s = Support.start(client, "fake");
        idp.respondTokens(null, "at-1");
        assertEquals("oauth.id_token_missing", codeOf(() -> client.complete(Support.cb(s.state(), "c"), s.binding())));
    }

    @Test
    @DisplayName("SSO-T-oauth-160 missing access_token rejected")
    void accessTokenMissing() {
        Support.Started s = Support.start(client, "fake");
        idp.tokenResponse = "{\"id_token\":\"x\"}";
        assertEquals("oauth.token_response_invalid", codeOf(() -> client.complete(Support.cb(s.state(), "c"), s.binding())));
    }

    @Test
    @DisplayName("SSO-T-oauth-161 non-bearer token_type rejected")
    void tokenType() {
        Support.Started s = Support.start(client, "fake");
        idp.tokenResponse = "{\"access_token\":\"a\",\"token_type\":\"mac\",\"id_token\":\"x\"}";
        assertEquals("oauth.token_type_invalid", codeOf(() -> client.complete(Support.cb(s.state(), "c"), s.binding())));
    }

    @Test
    @DisplayName("SSO-T-oauth-162 basic client authentication used when configured")
    void basicAuth() {
        ProviderConfig c = Support.generic(idp, "basic").clientAuth(ClientAuth.BASIC).build();
        OAuthClient cl = Support.client(clock, store, c);
        Support.Started s = Support.start(cl, "basic");
        issue(s, "u");
        cl.complete(Support.cb(s.state(), "c"), s.binding());
        assertTrue(idp.lastTokenAuthorization.startsWith("Basic "));
        assertNull(idp.lastForm.get("client_secret"));
    }

    @Test
    @DisplayName("SSO-T-oauth-163 token endpoint over plain http to non-loopback rejected")
    void httpTokenEndpoint() {
        ProviderConfig c = Support.generic(idp, "insecure").tokenEndpoint("http://idp.example.com/token").build();
        OAuthClient cl = Support.client(clock, store, c);
        Support.Started s = Support.start(cl, "insecure");
        assertEquals("oauth.url_insecure", codeOf(() -> cl.complete(Support.cb(s.state(), "c"), s.binding())));
    }

    @Test
    @DisplayName("SSO-T-oauth-164 authorization endpoint over plain http rejected at begin")
    void httpAuthorizationEndpoint() {
        ProviderConfig c = Support.generic(idp, "insecure").authorizationEndpoint("http://idp.example.com/authorize").build();
        OAuthClient cl = Support.client(clock, store, c);
        assertEquals("oauth.url_insecure", codeOf(() -> cl.begin("insecure", "/")));
    }

    @Test
    @DisplayName("SSO-T-oauth-165 redirectAfter must be a local absolute path")
    void redirectAfter() {
        for (String bad : new String[] {"//evil.example", "https://evil.example", "/\\evil.example", "evil", "/a\nb", "/" + "a".repeat(600)}) {
            assertEquals("oauth.redirect_after_invalid", codeOf(() -> client.begin("fake", bad)), bad);
        }
        assertEquals("/", store.consume(OAuthClient.hash(Support.query(client.begin("fake", null).authorizationUrl()).get("state"))).orElseThrow().redirectAfter());
        assertEquals("/a/b?x=1", OAuthClient.checkRedirectAfter("/a/b?x=1"));
    }

    @Test
    @DisplayName("SSO-T-oauth-166 unknown provider rejected")
    void unknownProvider() {
        assertEquals("oauth.provider_unknown", codeOf(() -> client.begin("nope", "/")));
        assertEquals("oauth.provider_unknown", codeOf(() -> client.begin(null, "/")));
    }

    @Test
    @DisplayName("SSO-T-oauth-167 extra authorization parameters cannot override security parameters")
    void extraParams() {
        ProviderConfig c = Support.generic(idp, "extra").extraAuthParams(Map.of("prompt", "select_account", "state", "evil")).build();
        OAuthClient cl = Support.client(clock, store, c);
        Support.Started s = Support.start(cl, "extra");
        assertEquals("select_account", s.query().get("prompt"));
        assertNotEquals("evil", s.state());
    }

    @Test
    @DisplayName("SSO-T-oauth-168 identity claims subset drops unknown and non-scalar claims")
    void claimsSubset() {
        Support.Started s = Support.start(client, "fake");
        Map<String, Object> claims = idp.idClaims(CLIENT, "u", s.nonce());
        claims.put("secret_internal", "x");
        claims.put("groups", java.util.List.of("a"));
        claims.put("hd", "example.com");
        idp.respondTokens(idp.sign(claims), "at");
        ExternalIdentity id = client.complete(Support.cb(s.state(), "c"), s.binding());
        assertFalse(id.claims().containsKey("secret_internal"));
        assertFalse(id.claims().containsKey("groups"));
        assertEquals("example.com", id.claims().get("hd"));
    }

    @Test
    @DisplayName("SSO-T-oauth-169 malicious email, name and picture are sanitized")
    void sanitize() {
        Support.Started s = Support.start(client, "fake");
        Map<String, Object> claims = idp.idClaims(CLIENT, "u", s.nonce());
        claims.put("email", "a<b>@example.com");
        claims.put("name", "Eve\u0000\n<b>x</b>");
        claims.put("picture", "javascript:alert(1)");
        idp.respondTokens(idp.sign(claims), "at");
        ExternalIdentity id = client.complete(Support.cb(s.state(), "c"), s.binding());
        assertNull(id.email());
        assertFalse(id.emailVerified());
        assertEquals("Eve<b>x</b>", id.name());
        assertNull(id.picture());
    }

    @Test
    @DisplayName("SSO-T-oauth-170 email is lower-cased and email_verified false stays false")
    void emailNormalize() {
        Support.Started s = Support.start(client, "fake");
        Map<String, Object> claims = idp.idClaims(CLIENT, "u", s.nonce());
        claims.put("email", "  Mixed.Case@Example.COM ");
        claims.put("email_verified", false);
        idp.respondTokens(idp.sign(claims), "at");
        ExternalIdentity id = client.complete(Support.cb(s.state(), "c"), s.binding());
        assertEquals("mixed.case@example.com", id.email());
        assertFalse(id.emailVerified());
    }

    @Test
    @DisplayName("SSO-T-oauth-171 email_verified as string true is understood, other types are false")
    void emailVerifiedTypes() {
        Support.Started s = Support.start(client, "fake");
        Map<String, Object> claims = idp.idClaims(CLIENT, "u", s.nonce());
        claims.put("email_verified", "true");
        idp.respondTokens(idp.sign(claims), "at");
        assertTrue(client.complete(Support.cb(s.state(), "c"), s.binding()).emailVerified());
        Support.Started t = Support.start(client, "fake");
        Map<String, Object> c2 = idp.idClaims(CLIENT, "u", t.nonce());
        c2.put("email_verified", 1L);
        idp.respondTokens(idp.sign(c2), "at");
        assertFalse(client.complete(Support.cb(t.state(), "c"), t.binding()).emailVerified());
    }
}
