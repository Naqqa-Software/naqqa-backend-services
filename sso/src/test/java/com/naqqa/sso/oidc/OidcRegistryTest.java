package com.naqqa.sso.oidc;

import com.naqqa.sso.SsoException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.naqqa.sso.oidc.OidcFixture.*;
import static org.junit.jupiter.api.Assertions.*;

class OidcRegistryTest {

    @Test
    @DisplayName("SSO-T-oidc-117 registered redirect URIs: https, loopback http and reverse-domain native schemes only")
    void redirectRegistration() {
        assertEquals("https://a.example.com/cb", RedirectUris.validateRegistered("https://a.example.com/cb"));
        assertDoesNotThrow(() -> RedirectUris.validateRegistered("http://127.0.0.1:5000/cb"));
        assertDoesNotThrow(() -> RedirectUris.validateRegistered("http://localhost/cb"));
        assertDoesNotThrow(() -> RedirectUris.validateRegistered("com.naqqa.app:/oauth/cb"));
        for (String bad : List.of("http://a.example.com/cb", "https://*.example.com/cb", "https://a.example.com/cb#frag",
                "https://user:pw@a.example.com/cb", "javascript:alert(1)", "myapp:/cb", "https://A.example.com/cb", "/relative",
                "https://a.example.com/c b", "data:text/html,x", "https://a.example.com/cb\\x", "")) {
            assertThrows(SsoException.class, () -> RedirectUris.validateRegistered(bad), bad);
        }
    }

    @Test
    @DisplayName("SSO-T-oidc-118 redirect_uri matching is exact string comparison")
    void redirectMatch() {
        List<String> reg = List.of("https://a.example.com/cb");
        assertTrue(RedirectUris.matches(reg, "https://a.example.com/cb"));
        for (String v : List.of("https://a.example.com/cb/", "https://a.example.com/cb?x", "https://a.example.com:443/cb",
                "https://a.example.com/CB", "https://a.example.com/%63b", "https://a.example.com/cb ")) {
            assertFalse(RedirectUris.matches(reg, v), v);
        }
        assertFalse(RedirectUris.matches(reg, null));
    }

    @Test
    @DisplayName("SSO-T-oidc-119 client registration invariants enforced")
    void clientInvariants() {
        assertThrows(SsoException.class, () -> ClientRegistration.builder(T1, "c").secret(ClientAuthMethod.CLIENT_SECRET_BASIC, null)
                .redirectUris(WEB_CB).build());
        assertThrows(SsoException.class, () -> new ClientRegistration(T1, "c", "c", ClientType.PUBLIC, ClientAuthMethod.NONE, "hash", null,
                List.of(WEB_CB), List.of(), java.util.Set.of(GrantTypes.AUTHORIZATION_CODE), java.util.Set.of("openid"), null, null, null,
                false, null, null, null, false, false, false, null, false));
        assertThrows(SsoException.class, () -> ClientRegistration.builder(T1, "c").privateKeyJwt(null).redirectUris(WEB_CB).build());
        assertThrows(SsoException.class, () -> ClientRegistration.builder(T1, "c").publicClient().grantTypes("password").build());
        assertThrows(SsoException.class, () -> ClientRegistration.builder(T1, "c").publicClient().build());
        assertThrows(SsoException.class, () -> ClientRegistration.builder(T1, "c").publicClient().redirectUris(WEB_CB)
                .accessTokenTtl(Duration.ofDays(2)).build());
        assertThrows(SsoException.class, () -> ClientRegistration.builder(T1, "bad id").publicClient().redirectUris(WEB_CB).build());
        assertThrows(SsoException.class, () -> ClientRegistration.builder(T1, "c").publicClient().redirectUris(WEB_CB)
                .backchannelLogoutUri("http://evil.example/bc").build());
        assertThrows(SsoException.class, () -> ClientRegistration.builder(T1, "c").publicClient().redirectUris(WEB_CB)
                .allowedAcr("urn:naqqa:acr:2").minAcr("urn:naqqa:acr:3").build());
    }

    @Test
    @DisplayName("SSO-T-oidc-120 PKCE S256 matches RFC 7636 appendix B vector")
    void pkceVector() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
        assertTrue(Pkce.verify("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk", "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"));
    }

    @Test
    @DisplayName("SSO-T-oidc-121 PKCE verifier syntax enforced (length 43-128, unreserved characters)")
    void pkceSyntax() {
        assertFalse(Pkce.validVerifier("a".repeat(42)));
        assertTrue(Pkce.validVerifier("a".repeat(43)));
        assertTrue(Pkce.validVerifier("a".repeat(128)));
        assertFalse(Pkce.validVerifier("a".repeat(129)));
        assertFalse(Pkce.validVerifier("a".repeat(42) + "+"));
        assertFalse(Pkce.verify(null, "x"));
        assertFalse(Pkce.validChallenge("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-c="));
    }

    @Test
    @DisplayName("SSO-T-oidc-122 discovery document lists exactly what is supported")
    void discovery() {
        OidcFixture f = new OidcFixture();
        Map<String, Object> d = f.provider.discovery().body();
        assertEquals(ISSUER, d.get("issuer"));
        assertEquals(ISSUER + "/oauth2/authorize", d.get("authorization_endpoint"));
        assertEquals(ISSUER + "/oauth2/token", d.get("token_endpoint"));
        assertEquals(ISSUER + "/oauth2/jwks", d.get("jwks_uri"));
        assertEquals(ISSUER + "/oauth2/logout", d.get("end_session_endpoint"));
        assertEquals(ISSUER + "/oauth2/device_authorization", d.get("device_authorization_endpoint"));
        assertEquals(List.of("code"), d.get("response_types_supported"));
        assertEquals(List.of("query"), d.get("response_modes_supported"));
        assertEquals(List.of("S256"), d.get("code_challenge_methods_supported"));
        assertEquals(List.of("RS256", "ES256", "EdDSA"), d.get("id_token_signing_alg_values_supported"));
        assertEquals(false, d.get("request_parameter_supported"));
        assertEquals(false, d.get("request_uri_parameter_supported"));
        assertEquals(false, d.get("frontchannel_logout_supported"));
        assertEquals(true, d.get("backchannel_logout_supported"));
        assertEquals(true, d.get("authorization_response_iss_parameter_supported"));
        assertFalse(d.containsKey("registration_endpoint"));
        assertFalse(((List<?>) d.get("grant_types_supported")).contains("implicit"));
        assertFalse(((List<?>) d.get("grant_types_supported")).contains("password"));
        assertFalse(((List<?>) d.get("token_endpoint_auth_methods_supported")).contains("client_secret_jwt"));
        assertEquals(ISSUER + "/.well-known/openid-configuration", f.config.discoveryUrl());
    }

    @Test
    @DisplayName("SSO-T-oidc-123 dynamic client registration disabled by default")
    void dcrDisabled() {
        OidcFixture f = new OidcFixture();
        OidcResponse r = f.provider.register(post(Map.of()).withTenant(T1).withHeader("Authorization", "Bearer " + INITIAL_TOKEN),
                "{\"redirect_uris\":[\"https://n.example.com/cb\"]}");
        assertEquals(404, r.status());
    }

    private OidcFixture dcr() {
        return new OidcFixture(OidcConfig.defaults(ISSUER).withDynamicRegistration(OidcProvider.hashInitialAccessToken(INITIAL_TOKEN)));
    }

    @Test
    @DisplayName("SSO-T-oidc-124 dynamic registration requires the initial access token and returns secret once")
    void dcrRegister() {
        OidcFixture f = dcr();
        assertTrue(f.provider.discovery().body().containsKey("registration_endpoint"));
        String meta = "{\"redirect_uris\":[\"https://n.example.com/cb\"],\"grant_types\":[\"authorization_code\",\"client_credentials\"],"
                + "\"scope\":\"openid profile\",\"client_name\":\"New\"}";
        assertEquals(401, f.provider.register(post(Map.of()).withTenant(T1).withHeader("Authorization", "Bearer wrong"), meta).status());
        assertEquals(401, f.provider.register(post(Map.of()).withTenant(T1), meta).status());
        OidcResponse r = f.provider.register(post(Map.of()).withTenant(T1).withHeader("Authorization", "Bearer " + INITIAL_TOKEN), meta);
        assertEquals(201, r.status(), r.bodyJson());
        String id = (String) r.body().get("client_id");
        String secret = (String) r.body().get("client_secret");
        ClientRegistration c = f.clients.find(id).orElseThrow();
        assertFalse(c.firstParty());
        assertTrue(c.dynamic());
        assertNotEquals(secret, c.secretHash());
        assertFalse(c.toString().contains(c.secretHash()));
        OidcResponse t = f.provider.token(post(params("grant_type", "client_credentials", "scope", "profile"))
                .withHeader("Authorization", basic(id, secret)));
        assertEquals("invalid_scope", t.error());
    }

    @Test
    @DisplayName("SSO-T-oidc-125 dynamic registration rejects jwks_uri, insecure redirects, implicit and foreign scopes")
    void dcrRejects() {
        OidcFixture f = dcr();
        OidcRequest req = post(Map.of()).withTenant(T1).withHeader("Authorization", "Bearer " + INITIAL_TOKEN);
        assertEquals("invalid_client_metadata", f.provider.register(req, "{\"redirect_uris\":[\"https://n.example.com/cb\"],"
                + "\"jwks_uri\":\"https://evil.example/jwks\"}").error());
        assertEquals("invalid_redirect_uri", f.provider.register(req, "{\"redirect_uris\":[\"http://n.example.com/cb\"]}").error());
        assertEquals("invalid_client_metadata", f.provider.register(req, "{\"redirect_uris\":[\"https://n.example.com/cb\"],"
                + "\"response_types\":[\"token\"]}").error());
        assertEquals("invalid_client_metadata", f.provider.register(req, "{\"redirect_uris\":[\"https://n.example.com/cb\"],"
                + "\"scope\":\"openid admin\"}").error());
        assertEquals("invalid_client_metadata", f.provider.register(req, "{\"redirect_uris\":[\"https://n.example.com/cb\"],"
                + "\"token_endpoint_auth_method\":\"none\",\"grant_types\":[\"client_credentials\"]}").error());
        assertEquals("invalid_client_metadata", f.provider.register(req, "not json").error());
        assertEquals("invalid_request", f.provider.register(post(Map.of()).withHeader("Authorization", "Bearer " + INITIAL_TOKEN),
                "{}").error());
    }

    @Test
    @DisplayName("SSO-T-oidc-126 issuer must be https (or loopback http) without trailing slash")
    void configValidation() {
        assertThrows(SsoException.class, () -> OidcConfig.defaults("http://sso.naqqa.test"));
        assertThrows(SsoException.class, () -> OidcConfig.defaults("https://sso.naqqa.test/"));
        assertThrows(SsoException.class, () -> OidcConfig.defaults("https://sso.naqqa.test?x=1"));
        assertDoesNotThrow(() -> OidcConfig.defaults("http://localhost:8080/api/auth"));
        OidcConfig c = OidcConfig.defaults(ISSUER);
        assertEquals(Duration.ofSeconds(60), c.codeTtl());
        assertEquals(Duration.ofMinutes(10), c.accessTokenTtl());
        assertFalse(c.dynamicRegistrationEnabled());
    }

    @Test
    @DisplayName("SSO-T-oidc-127 scope parsing rejects empty tokens, quotes and control characters")
    void scopeParsing() {
        assertEquals(List.of("openid", "profile"), List.copyOf(Scopes.parse("openid profile")));
        assertThrows(SsoException.class, () -> Scopes.parse("openid  profile"));
        assertThrows(SsoException.class, () -> Scopes.parse("openid\tprofile"));
        assertThrows(SsoException.class, () -> Scopes.parse("open\"id"));
        assertEquals("openid api.read email", Scopes.join(List.of("email", "api.read", "openid")));
    }

    @Test
    @DisplayName("SSO-T-oidc-128 purgeExpired drops expired codes, PAR handles and replay entries")
    void purge() {
        OidcFixture f = new OidcFixture();
        f.code(f.login(), verifier());
        f.provider.pushedAuthorization(post(authParams("web", WEB_CB, verifier())).withHeader("Authorization", basic("web", WEB_SECRET)));
        f.clock.advance(Duration.ofHours(2));
        assertTrue(f.provider.purgeExpired() >= 2);
    }

    @Test
    @DisplayName("SSO-T-oidc-129 provider builder requires all collaborators and matching key rings")
    void builderValidation() {
        OidcFixture f = new OidcFixture();
        assertThrows(SsoException.class, () -> OidcProvider.builder(f.clock, f.config).build());
        assertThrows(SsoException.class, () -> new TokenIssuer(f.clock, f.config, Map.of(), com.naqqa.sso.crypto.SigningAlgorithm.RS256));
        assertThrows(SsoException.class, () -> new TokenIssuer(f.clock, f.config,
                Map.of(com.naqqa.sso.crypto.SigningAlgorithm.RS256, com.naqqa.sso.crypto.KeyRing.of(f.clock, ES)),
                com.naqqa.sso.crypto.SigningAlgorithm.RS256));
    }

    @Test
    @DisplayName("SSO-T-oidc-130 audit trail records issuance and failures without secrets or tokens")
    void auditNoSecrets() {
        OidcFixture f = new OidcFixture();
        Map<String, Object> t = f.webTokens(f.login());
        f.provider.token(post(params("grant_type", "client_credentials")).withHeader("Authorization", basic("m2m", "bad-secret-value")));
        String all = f.auditStore.all().toString();
        assertTrue(all.contains("oidc.token"));
        assertFalse(all.contains(WEB_SECRET));
        assertFalse(all.contains((String) t.get("access_token")));
        assertFalse(all.contains((String) t.get("refresh_token")));
        assertFalse(all.contains("bad-secret-value"));
    }

    @Test
    @DisplayName("SSO-T-oidc-131 key rotation keeps old tokens verifiable and new tokens use the new kid")
    void keyRotation() {
        OidcFixture f = new OidcFixture();
        String old = (String) f.webTokens(f.login()).get("access_token");
        f.rsRing.rotate(com.naqqa.sso.crypto.SigningKey.generate(com.naqqa.sso.crypto.SigningAlgorithm.RS256), Duration.ofHours(1));
        String fresh = (String) f.webTokens(f.login()).get("access_token");
        assertNotEquals(f.header(old).get("kid"), f.header(fresh).get("kid"));
        assertEquals(200, f.provider.userinfo(get(Map.of()).withHeader("Authorization", "Bearer " + old)).status());
        assertEquals(4, com.naqqa.sso.jose.Jwks.parse(f.provider.jwks().bodyJson()).size());
    }

    @Test
    @DisplayName("SSO-T-oidc-132 client secret post with wrong secret never echoes it and Basic decoding is form-urlencoded")
    void secretPostAndBasicEncoding() {
        OidcFixture f = new OidcFixture();
        OidcResponse r = f.provider.token(post(params("grant_type", "client_credentials", "client_id", "m2m", "client_secret", "leak-me-not")));
        assertEquals(401, r.status());
        assertFalse(r.bodyJson().contains("leak-me-not"));
        assertNull(r.headers().get("WWW-Authenticate"));
        String special = "s%3Ae+c:r";
        f.clients.save(ClientRegistration.builder(T1, "sp").secret(ClientAuthMethod.CLIENT_SECRET_BASIC, HASHER.hash("s:e c"))
                .grantTypes(GrantTypes.CLIENT_CREDENTIALS).scopes("api.read").build());
        OidcResponse ok = f.provider.token(post(params("grant_type", "client_credentials"))
                .withHeader("Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString(
                        com.naqqa.sso.util.Bytes.utf8("sp:" + special.substring(0, special.length() - 2)))));
        assertEquals(200, ok.status(), ok.bodyJson());
    }

    @Test
    @DisplayName("SSO-T-oidc-140 back-channel logout URI: no private, link-local or metadata literals; dynamic clients get https on a public host only")
    void backchannelUriTargets() {
        for (String bad : List.of("https://169.254.169.254/bc", "https://10.0.0.5/bc", "https://192.168.1.1/bc", "https://[fd00::1]/bc",
                "https://0.0.0.0/bc", "https://[::ffff:169.254.169.254]/bc", "https://100.64.0.1/bc")) {
            assertThrows(SsoException.class, () -> ClientRegistration.builder(T1, "c").publicClient().redirectUris(WEB_CB)
                    .backchannelLogoutUri(bad).build(), bad);
        }
        assertDoesNotThrow(() -> ClientRegistration.builder(T1, "c").publicClient().redirectUris(WEB_CB)
                .backchannelLogoutUri("https://app.example.com/bc").build());
        assertDoesNotThrow(() -> ClientRegistration.builder(T1, "c").publicClient().redirectUris(WEB_CB)
                .backchannelLogoutUri("http://127.0.0.1:8080/bc").build());
        OidcFixture f = dcr();
        OidcRequest req = post(Map.of()).withTenant(T1).withHeader("Authorization", "Bearer " + INITIAL_TOKEN);
        for (String bad : List.of("http://127.0.0.1:8080/bc", "https://127.0.0.1/bc", "https://localhost/bc", "https://a.localhost/bc",
                "https://[::1]/bc", "https://169.254.169.254/bc", "http://localhost/bc")) {
            assertEquals("invalid_client_metadata", f.provider.register(req, "{\"redirect_uris\":[\"https://n.example.com/cb\"],"
                    + "\"backchannel_logout_uri\":\"" + bad + "\"}").error(), bad);
        }
        assertEquals(201, f.provider.register(req, "{\"redirect_uris\":[\"https://n.example.com/cb\"],"
                + "\"backchannel_logout_uri\":\"https://n.example.com/bc\"}").status());
    }
}
