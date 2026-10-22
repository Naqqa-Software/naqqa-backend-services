package com.naqqa.sso.oidc;

import com.naqqa.sso.crypto.KeyRing;
import com.naqqa.sso.crypto.SigningAlgorithm;
import com.naqqa.sso.jose.Jwks;
import com.naqqa.sso.jose.JwtValidator;
import com.naqqa.sso.json.Json;
import com.naqqa.sso.util.Bytes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.naqqa.sso.oidc.OidcFixture.*;
import static org.junit.jupiter.api.Assertions.*;

class OidcTokenTest {
    private final OidcFixture f = new OidcFixture();

    private OidcResponse webToken(Map<String, String> p) {
        return f.provider.token(post(p).withHeader("Authorization", basic("web", WEB_SECRET)));
    }

    private boolean active(String token) {
        OidcResponse r = f.provider.introspect(post(params("token", token)).withHeader("Authorization", basic("web", WEB_SECRET)));
        return Boolean.TRUE.equals(r.body().get("active"));
    }

    @Test
    @DisplayName("SSO-T-oidc-043 full code + PKCE flow returns access, refresh and id tokens with no-store")
    void fullFlow() {
        String v = verifier();
        OidcResponse r = f.exchange(f.code(f.login(), v), v);
        assertEquals(200, r.status());
        assertEquals("no-store", r.headers().get("Cache-Control"));
        assertEquals("Bearer", r.body().get("token_type"));
        assertEquals(600L, r.body().get("expires_in"));
        assertEquals("openid email profile", r.body().get("scope"));
        assertNotNull(r.body().get("refresh_token"));
        assertNotNull(r.body().get("id_token"));
    }

    @Test
    @DisplayName("SSO-T-oidc-044 access token is RFC 9068 at+jwt with required claims and verifies against JWKS")
    void accessTokenClaims() {
        Map<String, Object> t = f.webTokens(f.login());
        String at = (String) t.get("access_token");
        assertEquals("at+jwt", f.header(at).get("typ"));
        assertEquals("RS256", f.header(at).get("alg"));
        Map<String, Object> c = f.claims(at);
        assertEquals(ISSUER, c.get("iss"));
        assertEquals("alice", c.get("sub"));
        assertEquals(ISSUER, c.get("aud"));
        assertEquals("web", c.get("client_id"));
        assertEquals("openid email profile", c.get("scope"));
        assertEquals(600L, (Long) c.get("exp") - (Long) c.get("iat"));
        assertNotNull(c.get("jti"));
        assertEquals("urn:naqqa:acr:1", c.get("acr"));
        assertEquals(List.of("pwd"), c.get("amr"));
        assertNotNull(c.get("auth_time"));
        assertNotNull(c.get("sid"));
        assertEquals(T1, c.get("tid"));
        KeyRing published = Jwks.toKeyRing(f.provider.jwks().bodyJson(), f.clock);
        assertDoesNotThrow(() -> JwtValidator.builder(f.clock).issuer(ISSUER).audience(ISSUER).type("at+jwt").build().verify(at, published));
    }

    @Test
    @DisplayName("SSO-T-oidc-045 id_token carries nonce, at_hash, azp, sid, auth_time, acr, amr and no c_hash")
    void idTokenClaims() {
        String v = verifier();
        String s = f.login();
        OidcResponse a = f.provider.authorize(get(authParams("web", WEB_CB, v, "nonce", "nonce-123")).withSession(s));
        OidcResponse r = f.exchange(query(a.redirectUrl()).get("code"), v);
        String idt = (String) r.body().get("id_token");
        Map<String, Object> c = f.claims(idt);
        assertEquals("JWT", f.header(idt).get("typ"));
        assertEquals("nonce-123", c.get("nonce"));
        assertEquals("web", c.get("aud"));
        assertEquals("web", c.get("azp"));
        assertEquals(TokenIssuer.halfHash(SigningAlgorithm.RS256, (String) r.body().get("access_token")), c.get("at_hash"));
        assertEquals(f.claims((String) r.body().get("access_token")).get("sid"), c.get("sid"));
        assertNotNull(c.get("auth_time"));
        assertNotNull(c.get("acr"));
        assertNotNull(c.get("amr"));
        assertFalse(c.containsKey("c_hash"));
        assertFalse(c.containsKey("email"));
    }

    @Test
    @DisplayName("SSO-T-oidc-046 code replay is rejected and revokes tokens already issued from it")
    void codeReplay() {
        String v = verifier();
        String code = f.code(f.login(), v);
        OidcResponse first = f.exchange(code, v);
        String at = (String) first.body().get("access_token");
        String rt = (String) first.body().get("refresh_token");
        assertTrue(active(at));
        OidcResponse second = f.exchange(code, v);
        assertEquals(400, second.status());
        assertEquals("invalid_grant", second.error());
        assertFalse(active(at));
        assertFalse(active(rt));
        assertEquals("invalid_grant", webToken(params("grant_type", "refresh_token", "refresh_token", rt)).error());
        assertTrue(f.auditStore.all().stream().anyMatch(e -> e.event().action().equals("oidc.code_reuse")));
    }

    @Test
    @DisplayName("SSO-T-oidc-047 code issued to another client is rejected")
    void codeOtherClient() {
        String v = verifier();
        String s = f.login();
        f.consents.save(new ConsentRecord(T1, "alice", "spa", Set.of("openid", "profile", "email"), f.clock.instant()));
        OidcResponse a = f.provider.authorize(get(authParams("spa", SPA_CB, v)).withSession(s));
        String code = query(a.redirectUrl()).get("code");
        OidcResponse r = webToken(params("grant_type", "authorization_code", "code", code, "redirect_uri", SPA_CB, "code_verifier", v));
        assertEquals("invalid_grant", r.error());
    }

    @Test
    @DisplayName("SSO-T-oidc-048 PKCE verifier mismatch rejected and code is burned")
    void verifierMismatch() {
        String v = verifier();
        String code = f.code(f.login(), v);
        assertEquals("invalid_grant", f.exchange(code, verifier()).error());
        assertEquals("invalid_grant", f.exchange(code, v).error());
    }

    @Test
    @DisplayName("SSO-T-oidc-049 missing code_verifier or malformed verifier rejected")
    void verifierMissing() {
        String v = verifier();
        String code = f.code(f.login(), v);
        assertEquals("invalid_request", webToken(params("grant_type", "authorization_code", "code", code, "redirect_uri", WEB_CB)).error());
        String code2 = f.code(f.login(), v);
        assertEquals("invalid_grant", f.exchange(code2, "short").error());
    }

    @Test
    @DisplayName("SSO-T-oidc-050 redirect_uri at token endpoint must equal the authorize redirect_uri")
    void tokenRedirectMismatch() {
        String v = verifier();
        String code = f.code(f.login(), v);
        OidcResponse r = webToken(params("grant_type", "authorization_code", "code", code, "redirect_uri", WEB_CB + "/", "code_verifier", v));
        assertEquals("invalid_grant", r.error());
    }

    @Test
    @DisplayName("SSO-T-oidc-051 authorization code expires after 60 seconds")
    void codeExpiry() {
        String v = verifier();
        String code = f.code(f.login(), v);
        f.clock.advance(Duration.ofSeconds(61));
        assertEquals("invalid_grant", f.exchange(code, v).error());
    }

    @Test
    @DisplayName("SSO-T-oidc-052 wrong client secret yields 401 invalid_client with Basic challenge and no secret echo")
    void wrongSecret() {
        String v = verifier();
        String code = f.code(f.login(), v);
        OidcResponse r = f.provider.token(post(params("grant_type", "authorization_code", "code", code, "redirect_uri", WEB_CB,
                "code_verifier", v)).withHeader("Authorization", basic("web", "guess-secret-value")));
        assertEquals(401, r.status());
        assertEquals("invalid_client", r.error());
        assertTrue(r.headers().get("WWW-Authenticate").startsWith("Basic"));
        assertFalse(r.bodyJson().contains("guess-secret-value"));
        assertFalse(r.bodyJson().contains(WEB_SECRET));
    }

    @Test
    @DisplayName("SSO-T-oidc-053 client must use its registered authentication method")
    void authMethodEnforced() {
        OidcResponse r = f.provider.token(post(params("grant_type", "refresh_token", "refresh_token", "x", "client_id", "web",
                "client_secret", WEB_SECRET)));
        assertEquals("invalid_client", r.error());
        OidcResponse m = f.provider.token(post(params("grant_type", "client_credentials")).withHeader("Authorization", basic("m2m", M2M_SECRET)));
        assertEquals("invalid_client", m.error());
    }

    @Test
    @DisplayName("SSO-T-oidc-054 multiple client authentication methods in one request rejected")
    void multipleAuth() {
        OidcResponse r = f.provider.token(post(params("grant_type", "client_credentials", "client_secret", M2M_SECRET))
                .withHeader("Authorization", basic("m2m", M2M_SECRET)));
        assertEquals("invalid_request", r.error());
    }

    @Test
    @DisplayName("SSO-T-oidc-055 public client redeems with client_id only; confidential client cannot")
    void publicVsConfidential() {
        String v = verifier();
        String s = f.login();
        f.consents.save(new ConsentRecord(T1, "alice", "spa", Set.of("openid", "profile", "email"), f.clock.instant()));
        String code = query(f.provider.authorize(get(authParams("spa", SPA_CB, v)).withSession(s)).redirectUrl()).get("code");
        OidcResponse r = f.provider.token(post(params("grant_type", "authorization_code", "client_id", "spa", "code", code,
                "redirect_uri", SPA_CB, "code_verifier", v)));
        assertEquals(200, r.status());
        String code2 = f.code(s, v);
        OidcResponse c = f.provider.token(post(params("grant_type", "authorization_code", "client_id", "web", "code", code2,
                "redirect_uri", WEB_CB, "code_verifier", v)));
        assertEquals("invalid_client", c.error());
    }

    @Test
    @DisplayName("SSO-T-oidc-056 refresh token rotates; reuse revokes the whole family and its access tokens")
    void refreshRotationReuse() {
        Map<String, Object> t = f.webTokens(f.login());
        String rt1 = (String) t.get("refresh_token");
        OidcResponse r2 = webToken(params("grant_type", "refresh_token", "refresh_token", rt1));
        assertEquals(200, r2.status());
        String rt2 = (String) r2.body().get("refresh_token");
        String at2 = (String) r2.body().get("access_token");
        assertNotEquals(rt1, rt2);
        assertTrue(active(at2));
        OidcResponse reuse = webToken(params("grant_type", "refresh_token", "refresh_token", rt1));
        assertEquals("invalid_grant", reuse.error());
        assertEquals("invalid_grant", webToken(params("grant_type", "refresh_token", "refresh_token", rt2)).error());
        assertFalse(active(at2));
        assertFalse(active((String) t.get("access_token")));
    }

    @Test
    @DisplayName("SSO-T-oidc-057 refresh may narrow scope but escalation is invalid_scope and keeps token usable")
    void refreshScope() {
        Map<String, Object> t = f.webTokens(f.login());
        String rt = (String) t.get("refresh_token");
        OidcResponse esc = webToken(params("grant_type", "refresh_token", "refresh_token", rt, "scope", "openid profile email api.read"));
        assertEquals("invalid_scope", esc.error());
        OidcResponse narrow = webToken(params("grant_type", "refresh_token", "refresh_token", rt, "scope", "openid"));
        assertEquals(200, narrow.status());
        assertEquals("openid", narrow.body().get("scope"));
        OidcResponse back = webToken(params("grant_type", "refresh_token", "refresh_token", (String) narrow.body().get("refresh_token"),
                "scope", "openid email"));
        assertEquals(200, back.status());
        assertEquals("openid email", f.claims((String) back.body().get("access_token")).get("scope"));
    }

    @Test
    @DisplayName("SSO-T-oidc-058 refresh token presented by another client revokes the family")
    void refreshOtherClient() {
        Map<String, Object> t = f.webTokens(f.login());
        String rt = (String) t.get("refresh_token");
        OidcResponse r = f.provider.token(post(params("grant_type", "refresh_token", "refresh_token", rt, "client_id", "spa")));
        assertEquals("invalid_grant", r.error());
        assertEquals("invalid_grant", webToken(params("grant_type", "refresh_token", "refresh_token", rt)).error());
    }

    @Test
    @DisplayName("SSO-T-oidc-059 refresh id_token has no nonce and refresh fails once the user is removed")
    void refreshIdTokenAndUserRemoved() {
        Map<String, Object> t = f.webTokens(f.login());
        OidcResponse r = webToken(params("grant_type", "refresh_token", "refresh_token", (String) t.get("refresh_token")));
        assertFalse(f.claims((String) r.body().get("id_token")).containsKey("nonce"));
        f.users.remove(T1 + "/alice");
        assertEquals("invalid_grant", webToken(params("grant_type", "refresh_token", "refresh_token", (String) r.body().get("refresh_token"))).error());
    }

    @Test
    @DisplayName("SSO-T-oidc-060 client_credentials issues M2M access token without refresh or id_token")
    void clientCredentials() {
        OidcResponse r = f.provider.token(post(params("grant_type", "client_credentials", "client_id", "m2m", "client_secret", M2M_SECRET)));
        assertEquals(200, r.status());
        assertNull(r.body().get("refresh_token"));
        assertNull(r.body().get("id_token"));
        Map<String, Object> c = f.claims((String) r.body().get("access_token"));
        assertEquals("m2m", c.get("sub"));
        assertEquals("https://api.naqqa.test", c.get("aud"));
        assertEquals("api.read api.write", c.get("scope"));
        assertFalse(c.containsKey("sid"));
        assertFalse(c.containsKey("auth_time"));
    }

    @Test
    @DisplayName("SSO-T-oidc-061 client_credentials rejects openid and unregistered scopes")
    void clientCredentialsScope() {
        assertEquals("invalid_scope", f.provider.token(post(params("grant_type", "client_credentials", "client_id", "m2m",
                "client_secret", M2M_SECRET, "scope", "openid"))).error());
        assertEquals("invalid_scope", f.provider.token(post(params("grant_type", "client_credentials", "client_id", "m2m",
                "client_secret", M2M_SECRET, "scope", "api.admin"))).error());
        OidcResponse ok = f.provider.token(post(params("grant_type", "client_credentials", "client_id", "m2m",
                "client_secret", M2M_SECRET, "scope", "api.read")));
        assertEquals("api.read", ok.body().get("scope"));
    }

    @Test
    @DisplayName("SSO-T-oidc-062 public client cannot use client_credentials")
    void publicClientCredentials() {
        OidcResponse r = f.provider.token(post(params("grant_type", "client_credentials", "client_id", "spa")));
        assertEquals("unauthorized_client", r.error());
        assertThrows(com.naqqa.sso.SsoException.class, () -> ClientRegistration.builder(T1, "x").publicClient()
                .grantTypes(GrantTypes.CLIENT_CREDENTIALS).build());
    }

    @Test
    @DisplayName("SSO-T-oidc-063 password, implicit and token exchange grants are unsupported")
    void unsupportedGrants() {
        for (String g : List.of("password", "implicit", "urn:ietf:params:oauth:grant-type:token-exchange",
                "urn:ietf:params:oauth:grant-type:jwt-bearer")) {
            assertEquals("unsupported_grant_type", webToken(params("grant_type", g)).error());
        }
        assertEquals("invalid_request", webToken(params()).error());
        assertEquals("invalid_request", f.provider.token(get(params("grant_type", "client_credentials"))).error());
    }

    @Test
    @DisplayName("SSO-T-oidc-064 private_key_jwt client authentication works and tokens use the client's ES256 alg")
    void privateKeyJwt() {
        String a = f.clientAssertion(CLIENT_KEY, "pkjwt", f.config.tokenEndpoint(), Duration.ofMinutes(2), "jti-1");
        OidcResponse r = f.provider.token(post(params("grant_type", "client_credentials", "client_assertion_type",
                ClientAuthenticator.JWT_BEARER, "client_assertion", a, "scope", "api.read")));
        assertEquals(200, r.status(), r.bodyJson());
        assertEquals("ES256", f.header((String) r.body().get("access_token")).get("alg"));
    }

    @Test
    @DisplayName("SSO-T-oidc-065 private_key_jwt jti replay rejected")
    void privateKeyJwtReplay() {
        String a = f.clientAssertion(CLIENT_KEY, "pkjwt", f.config.tokenEndpoint(), Duration.ofMinutes(2), "jti-replay");
        Map<String, String> p = params("grant_type", "client_credentials", "client_assertion_type", ClientAuthenticator.JWT_BEARER,
                "client_assertion", a, "scope", "api.read");
        assertEquals(200, f.provider.token(post(p)).status());
        assertEquals("invalid_client", f.provider.token(post(p)).error());
    }

    @Test
    @DisplayName("SSO-T-oidc-066 private_key_jwt audience must be exactly the token endpoint")
    void privateKeyJwtAud() {
        for (String aud : List.of(ISSUER, f.config.introspectionEndpoint(), "https://evil.example/token")) {
            String a = f.clientAssertion(CLIENT_KEY, "pkjwt", aud, Duration.ofMinutes(2), "jti-" + Bytes.randomToken(4));
            assertEquals("invalid_client", f.provider.token(post(params("grant_type", "client_credentials", "client_assertion_type",
                    ClientAuthenticator.JWT_BEARER, "client_assertion", a))).error(), aud);
        }
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("iss", "pkjwt");
        c.put("sub", "pkjwt");
        c.put("aud", List.of(f.config.tokenEndpoint(), "https://evil.example"));
        c.put("jti", "multi-aud");
        c.put("exp", f.clock.instant().plusSeconds(60).getEpochSecond());
        String multi = com.naqqa.sso.jose.Jwt.sign(CLIENT_KEY, null, c);
        assertEquals("invalid_client", f.provider.token(post(params("grant_type", "client_credentials", "client_assertion_type",
                ClientAuthenticator.JWT_BEARER, "client_assertion", multi))).error());
    }

    @Test
    @DisplayName("SSO-T-oidc-067 private_key_jwt lifetime over 5 minutes or expired rejected")
    void privateKeyJwtLifetime() {
        String longLived = f.clientAssertion(CLIENT_KEY, "pkjwt", f.config.tokenEndpoint(), Duration.ofMinutes(30), "jti-long");
        assertEquals("invalid_client", f.provider.token(post(params("grant_type", "client_credentials", "client_assertion_type",
                ClientAuthenticator.JWT_BEARER, "client_assertion", longLived))).error());
        String a = f.clientAssertion(CLIENT_KEY, "pkjwt", f.config.tokenEndpoint(), Duration.ofMinutes(1), "jti-exp");
        f.clock.advance(Duration.ofMinutes(3));
        assertEquals("invalid_client", f.provider.token(post(params("grant_type", "client_credentials", "client_assertion_type",
                ClientAuthenticator.JWT_BEARER, "client_assertion", a))).error());
    }

    @Test
    @DisplayName("SSO-T-oidc-068 private_key_jwt with alg none, alg switch to HS256 or attacker key rejected")
    void privateKeyJwtAlgAttacks() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("iss", "pkjwt");
        c.put("sub", "pkjwt");
        c.put("aud", f.config.tokenEndpoint());
        c.put("jti", "alg-attack");
        c.put("exp", f.clock.instant().plusSeconds(60).getEpochSecond());
        String none = unsigned(Map.of("alg", "none", "kid", CLIENT_KEY.kid()), c);
        Map<String, Object> hsHeader = new LinkedHashMap<>();
        hsHeader.put("alg", "HS256");
        hsHeader.put("kid", CLIENT_KEY.kid());
        String input = Bytes.b64url(Json.writeUtf8(hsHeader)) + "." + Bytes.b64url(Json.writeUtf8(c));
        String hs = input + "." + Bytes.b64url(com.naqqa.sso.util.Hashes.hmacSha256(Bytes.utf8(FIXTURE_JWKS()), Bytes.utf8(input)));
        String attacker = com.naqqa.sso.jose.Jwt.sign(ATTACKER_KEY.withKid(CLIENT_KEY.kid()), null, c);
        for (String a : List.of(none, hs, attacker)) {
            assertEquals("invalid_client", f.provider.token(post(params("grant_type", "client_credentials", "client_assertion_type",
                    ClientAuthenticator.JWT_BEARER, "client_assertion", a))).error());
        }
    }

    private static String FIXTURE_JWKS() {
        return OidcFixture.Jwks(CLIENT_KEY);
    }

    @Test
    @DisplayName("SSO-T-oidc-069 private_key_jwt with iss or sub of another client rejected")
    void privateKeyJwtSubject() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("iss", "pkjwt");
        c.put("sub", "web");
        c.put("aud", f.config.tokenEndpoint());
        c.put("jti", "sub-mismatch");
        c.put("exp", f.clock.instant().plusSeconds(60).getEpochSecond());
        String a = com.naqqa.sso.jose.Jwt.sign(CLIENT_KEY, null, c);
        assertEquals("invalid_client", f.provider.token(post(params("grant_type", "client_credentials", "client_id", "pkjwt",
                "client_assertion_type", ClientAuthenticator.JWT_BEARER, "client_assertion", a))).error());
        String wrongType = f.clientAssertion(CLIENT_KEY, "pkjwt", f.config.tokenEndpoint(), Duration.ofMinutes(1), "jti-type");
        assertEquals("invalid_client", f.provider.token(post(params("grant_type", "client_credentials", "client_assertion_type",
                "urn:ietf:params:oauth:client-assertion-type:saml2-bearer", "client_assertion", wrongType))).error());
    }

    @Test
    @DisplayName("SSO-T-oidc-070 unknown client with secret is rejected as invalid_client")
    void unknownClientSecret() {
        OidcResponse r = f.provider.token(post(params("grant_type", "client_credentials")).withHeader("Authorization", basic("ghost", "whatever-secret")));
        assertEquals(401, r.status());
        assertEquals("invalid_client", r.error());
    }

    @Test
    @DisplayName("SSO-T-oidc-071 client of another tenant cannot use the token endpoint of this tenant")
    void crossTenantTokenEndpoint() {
        OidcResponse r = f.provider.token(post(params("grant_type", "client_credentials", "client_id", "m2m", "client_secret", M2M_SECRET))
                .withTenant(T2));
        assertEquals("invalid_client", r.error());
        String v = verifier();
        String code = f.code(f.login(), v);
        OidcResponse x = f.provider.token(post(params("grant_type", "authorization_code", "code", code, "redirect_uri", WEB_CB,
                "code_verifier", v)).withHeader("Authorization", basic("other", OTHER_SECRET)));
        assertEquals("invalid_grant", x.error());
    }

    @Test
    @DisplayName("SSO-T-oidc-072 JWKS publishes RS256, ES256 and EdDSA public keys without private members")
    void jwksPublished() {
        OidcResponse r = f.provider.jwks();
        String json = r.bodyJson();
        assertEquals(3, Jwks.parse(json).size());
        assertFalse(json.contains("\"d\""));
        assertTrue(r.headers().get("Cache-Control").startsWith("public"));
    }

    @Test
    @DisplayName("SSO-T-oidc-073 per-client EdDSA signing alg applies to id_token and at_hash uses SHA-512")
    void eddsaClient() {
        f.clients.save(f.clients.find("web").orElseThrow().toBuilder().signingAlg(SigningAlgorithm.EdDSA).build());
        Map<String, Object> t = f.webTokens(f.login());
        String idt = (String) t.get("id_token");
        assertEquals("EdDSA", f.header(idt).get("alg"));
        assertEquals(TokenIssuer.halfHash(SigningAlgorithm.EdDSA, (String) t.get("access_token")), f.claims(idt).get("at_hash"));
        assertEquals(43, ((String) f.claims(idt).get("at_hash")).length());
    }

    @Test
    @DisplayName("SSO-T-oidc-074 token request for a code whose session was logged out is rejected")
    void codeAfterLogout() {
        String s = f.login();
        String v = verifier();
        String code = f.code(s, v);
        f.provider.sessionEnded(f.sessions.validate(s));
        assertEquals("invalid_grant", f.exchange(code, v).error());
    }
}
