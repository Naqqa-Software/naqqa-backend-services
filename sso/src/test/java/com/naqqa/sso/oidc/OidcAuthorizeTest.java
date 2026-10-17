package com.naqqa.sso.oidc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.naqqa.sso.oidc.OidcFixture.*;
import static org.junit.jupiter.api.Assertions.*;

class OidcAuthorizeTest {
    private final OidcFixture f = new OidcFixture();

    private OidcResponse auth(String session, Map<String, String> p) {
        return f.provider.authorize(get(p).withSession(session));
    }

    private void assertErrorPage(OidcResponse r) {
        assertEquals(OidcResponse.Kind.ERROR_PAGE, r.kind());
        assertNull(r.redirectUrl());
    }

    private Map<String, String> redirectError(OidcResponse r, String error) {
        assertEquals(OidcResponse.Kind.REDIRECT, r.kind(), String.valueOf(r.body()));
        Map<String, String> q = query(r.redirectUrl());
        assertEquals(error, q.get("error"), r.redirectUrl());
        assertNull(q.get("code"));
        assertEquals(ISSUER, q.get("iss"));
        return q;
    }

    @Test
    @DisplayName("SSO-T-oidc-001 authorize with valid session redirects with code, state and iss (RFC 9207)")
    void happyPath() {
        String s = f.login();
        Map<String, String> p = authParams("web", WEB_CB, verifier());
        OidcResponse r = auth(s, p);
        assertEquals(OidcResponse.Kind.REDIRECT, r.kind());
        assertEquals(WEB_CB, base(r.redirectUrl()));
        Map<String, String> q = query(r.redirectUrl());
        assertEquals(43, q.get("code").length());
        assertEquals(p.get("state"), q.get("state"));
        assertEquals(ISSUER, q.get("iss"));
    }

    @Test
    @DisplayName("SSO-T-oidc-002 authorization code stored only as hash")
    void codeHashed() {
        String code = f.code(f.login(), verifier());
        assertTrue(f.store.consumeCode(code).isEmpty());
        assertTrue(f.store.consumeCode(Hashing.token(code)).isPresent());
    }

    @Test
    @DisplayName("SSO-T-oidc-003 no session returns LOGIN interaction with login_hint and pending handle")
    void noSession() {
        OidcResponse r = auth(null, authParams("web", WEB_CB, verifier(), "login_hint", "alice@example.com"));
        assertEquals(OidcResponse.Kind.INTERACTION, r.kind());
        assertEquals(Interaction.Type.LOGIN, r.interaction().type());
        assertEquals("alice@example.com", r.interaction().loginHint());
        assertEquals(43, r.interaction().handle().length());
    }

    @Test
    @DisplayName("SSO-T-oidc-004 resume after login issues code bound to original request")
    void resumeAfterLogin() {
        Map<String, String> p = authParams("web", WEB_CB, verifier());
        OidcResponse r = auth(null, p);
        String s = f.login();
        OidcResponse done = f.provider.resumeAuthorization(r.interaction().handle(), get(Map.of()).withSession(s));
        assertEquals(OidcResponse.Kind.REDIRECT, done.kind());
        assertEquals(p.get("state"), query(done.redirectUrl()).get("state"));
        OidcResponse again = f.provider.resumeAuthorization(r.interaction().handle(), get(Map.of()).withSession(s));
        assertErrorPage(again);
    }

    @Test
    @DisplayName("SSO-T-oidc-005 redirect_uri trailing slash variant rejected without redirect")
    void redirectTrailingSlash() {
        assertErrorPage(auth(f.login(), authParams("web", WEB_CB + "/", verifier())));
    }

    @Test
    @DisplayName("SSO-T-oidc-006 redirect_uri case variant of host or path rejected")
    void redirectCase() {
        assertErrorPage(auth(f.login(), authParams("web", "https://APP.example.com/cb", verifier())));
        assertErrorPage(auth(f.login(), authParams("web", "https://app.example.com/CB", verifier())));
        assertErrorPage(auth(f.login(), authParams("web", "HTTPS://app.example.com/cb", verifier())));
    }

    @Test
    @DisplayName("SSO-T-oidc-007 redirect_uri with extra query or fragment rejected")
    void redirectExtraQuery() {
        assertErrorPage(auth(f.login(), authParams("web", WEB_CB + "?next=https://evil.example", verifier())));
        assertErrorPage(auth(f.login(), authParams("web", WEB_CB + "#x", verifier())));
    }

    @Test
    @DisplayName("SSO-T-oidc-008 redirect_uri userinfo@host trick rejected")
    void redirectUserinfo() {
        assertErrorPage(auth(f.login(), authParams("web", "https://app.example.com@evil.example/cb", verifier())));
        assertErrorPage(auth(f.login(), authParams("web", "https://evil.example@app.example.com/cb", verifier())));
    }

    @Test
    @DisplayName("SSO-T-oidc-009 redirect_uri percent-encoded variants rejected")
    void redirectEncoded() {
        assertErrorPage(auth(f.login(), authParams("web", "https://app.example.com/%63b", verifier())));
        assertErrorPage(auth(f.login(), authParams("web", "https://app.example.com/cb%2F..%2Fevil", verifier())));
        assertErrorPage(auth(f.login(), authParams("web", "https://app.example.com%2eevil.example/cb", verifier())));
    }

    @Test
    @DisplayName("SSO-T-oidc-010 redirect_uri different or default port rejected, registered port accepted")
    void redirectPort() {
        assertErrorPage(auth(f.login(), authParams("web", "https://app.example.com:443/cb", verifier())));
        assertErrorPage(auth(f.login(), authParams("web", "https://app.example.com:8444/cb", verifier())));
        OidcResponse ok = auth(f.login(), authParams("web", "https://app.example.com:8443/cb", verifier()));
        assertEquals(OidcResponse.Kind.REDIRECT, ok.kind());
        assertTrue(ok.redirectUrl().startsWith("https://app.example.com:8443/cb?code="));
    }

    @Test
    @DisplayName("SSO-T-oidc-011 redirect_uri missing or registered for another client is rejected")
    void redirectMissingOrOtherClient() {
        assertErrorPage(auth(f.login(), authParams("web", WEB_CB, verifier(), "redirect_uri", null)));
        assertErrorPage(auth(f.login(), authParams("web", SPA_CB, verifier())));
    }

    @Test
    @DisplayName("SSO-T-oidc-012 open redirect via error: invalid request with bad redirect_uri never redirects")
    void openRedirectViaError() {
        OidcResponse r = auth(null, authParams("web", "https://evil.example/cb", verifier(), "response_type", "token"));
        assertErrorPage(r);
        assertFalse(String.valueOf(r.body()).contains("evil.example"));
    }

    @Test
    @DisplayName("SSO-T-oidc-013 unknown client renders error page")
    void unknownClient() {
        assertErrorPage(auth(null, authParams("nope", WEB_CB, verifier())));
        assertErrorPage(auth(null, authParams("web", WEB_CB, verifier(), "client_id", null)));
    }

    @Test
    @DisplayName("SSO-T-oidc-014 implicit and hybrid response types rejected with unsupported_response_type")
    void implicitRejected() {
        for (String rt : List.of("token", "id_token", "code id_token", "code token", "id_token token")) {
            redirectError(auth(f.login(), authParams("web", WEB_CB, verifier(), "response_type", rt)), "unsupported_response_type");
        }
        redirectError(auth(f.login(), authParams("web", WEB_CB, verifier(), "response_type", null)), "invalid_request");
    }

    @Test
    @DisplayName("SSO-T-oidc-015 missing PKCE challenge rejected")
    void pkceMissing() {
        redirectError(auth(f.login(), authParams("web", WEB_CB, verifier(), "code_challenge", null, "code_challenge_method", null)),
                "invalid_request");
    }

    @Test
    @DisplayName("SSO-T-oidc-016 PKCE plain method and absent method rejected")
    void pkcePlain() {
        String v = verifier();
        redirectError(auth(f.login(), authParams("web", WEB_CB, v, "code_challenge", v, "code_challenge_method", "plain")), "invalid_request");
        redirectError(auth(f.login(), authParams("web", WEB_CB, v, "code_challenge_method", null)), "invalid_request");
        redirectError(auth(f.login(), authParams("web", WEB_CB, v, "code_challenge", "short", "code_challenge_method", "S256")), "invalid_request");
    }

    @Test
    @DisplayName("SSO-T-oidc-017 state is passed through on errors and absent state is omitted")
    void stateHandling() {
        Map<String, String> q = redirectError(auth(f.login(), authParams("web", WEB_CB, verifier(), "scope", "openid admin", "state", "abc")),
                "invalid_scope");
        assertEquals("abc", q.get("state"));
        OidcResponse r = auth(f.login(), authParams("web", WEB_CB, verifier(), "state", null));
        assertEquals(OidcResponse.Kind.REDIRECT, r.kind());
        assertFalse(query(r.redirectUrl()).containsKey("state"));
    }

    @Test
    @DisplayName("SSO-T-oidc-018 client requiring state rejects requests without state")
    void stateRequired() {
        f.clients.save(f.clients.find("web").orElseThrow().toBuilder().requireState(true).build());
        redirectError(auth(f.login(), authParams("web", WEB_CB, verifier(), "state", null)), "invalid_request");
    }

    @Test
    @DisplayName("SSO-T-oidc-019 scope not allowed for client yields invalid_scope")
    void scopeEscalation() {
        redirectError(auth(f.login(), authParams("spa", SPA_CB, verifier(), "scope", "openid offline_access")), "invalid_scope");
        redirectError(auth(f.login(), authParams("web", WEB_CB, verifier(), "scope", null)), "invalid_scope");
    }

    @Test
    @DisplayName("SSO-T-oidc-020 request object parameter rejected with request_not_supported")
    void requestObject() {
        redirectError(auth(f.login(), authParams("web", WEB_CB, verifier(), "request", "eyJhbGciOiJub25lIn0.e30.")), "request_not_supported");
    }

    @Test
    @DisplayName("SSO-T-oidc-021 non-PAR request_uri rejected with request_uri_not_supported")
    void requestUri() {
        redirectError(auth(f.login(), authParams("web", WEB_CB, verifier(), "request_uri", "https://evil.example/req")),
                "request_uri_not_supported");
        assertErrorPage(auth(f.login(), authParams("web", "https://evil.example/cb", verifier(), "request_uri", "https://evil.example/req")));
    }

    @Test
    @DisplayName("SSO-T-oidc-022 prompt=none without session returns login_required")
    void promptNoneNoSession() {
        redirectError(auth(null, authParams("web", WEB_CB, verifier(), "prompt", "none")), "login_required");
    }

    @Test
    @DisplayName("SSO-T-oidc-023 prompt=none with session issues code silently (SSO)")
    void promptNoneSso() {
        OidcResponse r = auth(f.login(), authParams("web", WEB_CB, verifier(), "prompt", "none"));
        assertNotNull(query(r.redirectUrl()).get("code"));
    }

    @Test
    @DisplayName("SSO-T-oidc-024 prompt=none combined with login is invalid_request; unknown prompt rejected")
    void promptCombination() {
        redirectError(auth(f.login(), authParams("web", WEB_CB, verifier(), "prompt", "none login")), "invalid_request");
        redirectError(auth(f.login(), authParams("web", WEB_CB, verifier(), "prompt", "create")), "invalid_request");
    }

    @Test
    @DisplayName("SSO-T-oidc-025 prompt=login forces re-authentication even with valid session")
    void promptLogin() {
        String s = f.login();
        OidcResponse r = auth(s, authParams("web", WEB_CB, verifier(), "prompt", "login"));
        assertEquals(Interaction.Type.LOGIN, r.interaction().type());
        assertTrue(r.interaction().forceLogin());
        OidcResponse stale = f.provider.resumeAuthorization(r.interaction().handle(), get(Map.of()).withSession(s));
        assertEquals(OidcResponse.Kind.INTERACTION, stale.kind());
        f.clock.advance(Duration.ofSeconds(5));
        f.sessions.reauthenticate(s, Set.of("pwd"), null);
        OidcResponse done = f.provider.resumeAuthorization(r.interaction().handle(), get(Map.of()).withSession(s));
        assertEquals(OidcResponse.Kind.REDIRECT, done.kind());
    }

    @Test
    @DisplayName("SSO-T-oidc-026 prompt=select_account asks for account selection even with a session")
    void selectAccount() {
        OidcResponse r = auth(f.login(), authParams("web", WEB_CB, verifier(), "prompt", "select_account"));
        assertEquals(Interaction.Type.SELECT_ACCOUNT, r.interaction().type());
    }

    @Test
    @DisplayName("SSO-T-oidc-027 max_age exceeded requires login, within max_age reuses session")
    void maxAge() {
        String s = f.login();
        f.clock.advance(Duration.ofMinutes(10));
        assertEquals(OidcResponse.Kind.REDIRECT, auth(s, authParams("web", WEB_CB, verifier(), "max_age", "3600")).kind());
        OidcResponse r = auth(s, authParams("web", WEB_CB, verifier(), "max_age", "60"));
        assertEquals(Interaction.Type.LOGIN, r.interaction().type());
        redirectError(auth(s, authParams("web", WEB_CB, verifier(), "max_age", "60", "prompt", "none")), "login_required");
        redirectError(auth(s, authParams("web", WEB_CB, verifier(), "max_age", "-1")), "invalid_request");
    }

    @Test
    @DisplayName("SSO-T-oidc-028 acr_values above session level produces STEP_UP interaction with required acr")
    void acrStepUp() {
        String s = f.login();
        OidcResponse r = auth(s, authParams("web", WEB_CB, verifier(), "acr_values", "urn:naqqa:acr:2"));
        assertEquals(Interaction.Type.STEP_UP, r.interaction().type());
        assertEquals("urn:naqqa:acr:2", r.interaction().requiredAcr());
        f.sessions.reauthenticate(s, Set.of("pwd", "otp"), "urn:naqqa:acr:2");
        OidcResponse done = f.provider.resumeAuthorization(r.interaction().handle(), get(Map.of()).withSession(s));
        assertEquals(OidcResponse.Kind.REDIRECT, done.kind());
    }

    @Test
    @DisplayName("SSO-T-oidc-029 acr insufficient with prompt=none yields interaction_required")
    void acrPromptNone() {
        redirectError(auth(f.login(), authParams("web", WEB_CB, verifier(), "acr_values", "urn:naqqa:acr:3", "prompt", "none")),
                "interaction_required");
    }

    @Test
    @DisplayName("SSO-T-oidc-030 unknown acr_values rejected; client minimum acr enforced")
    void acrUnknownAndMin() {
        redirectError(auth(f.login(), authParams("web", WEB_CB, verifier(), "acr_values", "urn:evil:acr")), "invalid_request");
        f.clients.save(f.clients.find("web").orElseThrow().toBuilder().minAcr("urn:naqqa:acr:2").build());
        assertEquals(Interaction.Type.STEP_UP, auth(f.login(), authParams("web", WEB_CB, verifier())).interaction().type());
        String mfa = f.login(T1, "alice", Set.of("pwd", "otp"));
        assertEquals(OidcResponse.Kind.REDIRECT, auth(mfa, authParams("web", WEB_CB, verifier(), "acr_values", "urn:naqqa:acr:1")).kind());
    }

    @Test
    @DisplayName("SSO-T-oidc-031 third-party client requires consent, approval stored and reused")
    void consentFlow() {
        String s = f.login();
        OidcResponse r = auth(s, authParams("spa", SPA_CB, verifier()));
        assertEquals(Interaction.Type.CONSENT, r.interaction().type());
        assertEquals(Set.of("openid", "profile", "email"), r.interaction().scopes());
        OidcResponse done = f.provider.completeConsent(r.interaction().handle(), get(Map.of()).withSession(s), true);
        assertNotNull(query(done.redirectUrl()).get("code"));
        ConsentRecord c = f.consents.find(T1, "alice", "spa").orElseThrow();
        assertEquals(f.clock.instant(), c.grantedAt());
        assertEquals(OidcResponse.Kind.REDIRECT, auth(s, authParams("spa", SPA_CB, verifier())).kind());
        assertEquals(Interaction.Type.CONSENT, auth(s, authParams("spa", SPA_CB, verifier(), "prompt", "consent")).interaction().type());
        assertEquals(Interaction.Type.CONSENT, auth(s, authParams("spa", SPA_CB, verifier(), "scope", "openid api.read")).interaction().type());
    }

    @Test
    @DisplayName("SSO-T-oidc-032 consent denied redirects access_denied; prompt=none without consent yields consent_required")
    void consentDenied() {
        String s = f.login();
        OidcResponse r = auth(s, authParams("spa", SPA_CB, verifier()));
        redirectError(f.provider.completeConsent(r.interaction().handle(), get(Map.of()).withSession(s), false), "access_denied");
        redirectError(auth(s, authParams("spa", SPA_CB, verifier(), "prompt", "none")), "consent_required");
    }

    @Test
    @DisplayName("SSO-T-oidc-033 consent cannot be completed without a session")
    void consentNeedsSession() {
        OidcResponse r = auth(f.login(), authParams("spa", SPA_CB, verifier()));
        OidcResponse x = f.provider.completeConsent(r.interaction().handle(), get(Map.of()), true);
        assertEquals(Interaction.Type.LOGIN, x.interaction().type());
        assertTrue(f.consents.find(T1, "alice", "spa").isEmpty());
    }

    @Test
    @DisplayName("SSO-T-oidc-034 first-party client skips consent")
    void firstPartySkipsConsent() {
        assertEquals(OidcResponse.Kind.REDIRECT, auth(f.login(), authParams("web", WEB_CB, verifier(), "prompt", "consent")).kind());
    }

    @Test
    @DisplayName("SSO-T-oidc-035 repeated parameters rejected")
    void repeatedParams() {
        Map<String, List<String>> p = new java.util.LinkedHashMap<>();
        authParams("web", WEB_CB, verifier()).forEach((k, v) -> p.put(k, List.of(v)));
        p.put("scope", List.of("openid", "openid profile"));
        OidcResponse r = f.provider.authorize(new OidcRequest("GET", p, Map.of(), f.login(), null, null, null));
        redirectError(r, "invalid_request");
        p.put("redirect_uri", List.of(WEB_CB, "https://evil.example/cb"));
        assertErrorPage(f.provider.authorize(new OidcRequest("GET", p, Map.of(), f.login(), null, null, null)));
    }

    @Test
    @DisplayName("SSO-T-oidc-036 nonce replay for the same client rejected")
    void nonceReplay() {
        String s = f.login();
        assertEquals(OidcResponse.Kind.REDIRECT, auth(s, authParams("web", WEB_CB, verifier(), "nonce", "fixed-nonce")).kind());
        redirectError(auth(s, authParams("web", WEB_CB, verifier(), "nonce", "fixed-nonce")), "invalid_request");
    }

    @Test
    @DisplayName("SSO-T-oidc-037 session of another tenant is not reused across tenants")
    void crossTenantSession() {
        String carol = f.login(T2, "carol", Set.of("pwd"));
        OidcResponse r = auth(carol, authParams("web", WEB_CB, verifier()));
        assertEquals(Interaction.Type.LOGIN, r.interaction().type());
        assertErrorPage(f.provider.authorize(get(authParams("web", WEB_CB, verifier())).withTenant(T2)));
    }

    @Test
    @DisplayName("SSO-T-oidc-038 response_mode other than query rejected")
    void responseMode() {
        redirectError(auth(f.login(), authParams("web", WEB_CB, verifier(), "response_mode", "fragment")), "invalid_request");
        redirectError(auth(f.login(), authParams("web", WEB_CB, verifier(), "response_mode", "form_post")), "invalid_request");
    }

    @Test
    @DisplayName("SSO-T-oidc-039 client without authorization_code grant gets unauthorized_client")
    void unauthorizedClient() {
        f.clients.save(ClientRegistration.builder(T1, "devonly").publicClient().redirectUris("https://d.example.com/cb")
                .grantTypes(GrantTypes.DEVICE_CODE).scopes("openid").build());
        redirectError(auth(f.login(), authParams("devonly", "https://d.example.com/cb", verifier(), "scope", "openid")), "unauthorized_client");
    }

    @Test
    @DisplayName("SSO-T-oidc-040 SSO across apps: authorize adds client to session clientIds")
    void ssoTracksClients() {
        String s = f.login();
        auth(s, authParams("web", WEB_CB, verifier()));
        f.consents.save(new ConsentRecord(T1, "alice", "spa", Set.of("openid", "profile", "email"), f.clock.instant()));
        auth(s, authParams("spa", SPA_CB, verifier()));
        assertTrue(f.sessions.validate(s).clientIds().containsAll(Set.of("web", "spa")));
    }

    @Test
    @DisplayName("SSO-T-oidc-041 expired pending authorization cannot be resumed")
    void pendingExpires() {
        OidcResponse r = auth(null, authParams("web", WEB_CB, verifier()));
        f.clock.advance(Duration.ofMinutes(11));
        assertErrorPage(f.provider.resumeAuthorization(r.interaction().handle(), get(Map.of()).withSession(f.login())));
        assertErrorPage(f.provider.resumeAuthorization("bogus", get(Map.of())));
    }

    @Test
    @DisplayName("SSO-T-oidc-042 client requiring PAR rejects plain authorize")
    void requireParRejectsPlain() {
        f.clients.save(f.clients.find("web").orElseThrow().toBuilder().requirePar(true).build());
        redirectError(auth(f.login(), authParams("web", WEB_CB, verifier())), "invalid_request");
    }

    @Test
    @DisplayName("SSO-T-oidc-143 F-CORE-29 a consent revocation landing while the user approves another consent screen is not undone: scopes revoked are not written back")
    void consentRevocationNotUndone() {
        String s = f.login();
        f.consents.save(new ConsentRecord(T1, "alice", "spa", Set.of("openid", "api.read"), f.clock.instant()));
        OidcResponse r = auth(s, authParams("spa", SPA_CB, verifier(), "prompt", "consent"));
        assertEquals(Interaction.Type.CONSENT, r.interaction().type());
        f.consents.beforeWrite = () -> assertTrue(f.consents.revoke(T1, "alice", "spa"));
        OidcResponse done = f.provider.completeConsent(r.interaction().handle(), get(Map.of()).withSession(s), true);
        assertNotNull(query(done.redirectUrl()).get("code"));
        assertEquals(Set.of("openid", "profile", "email"), f.consents.find(T1, "alice", "spa").orElseThrow().scopes());
    }
}
