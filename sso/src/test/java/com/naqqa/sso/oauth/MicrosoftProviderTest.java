package com.naqqa.sso.oauth;

import com.naqqa.sso.crypto.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static com.naqqa.sso.oauth.Support.CLIENT;
import static com.naqqa.sso.oauth.Support.codeOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MicrosoftProviderTest {
    private static final String TENANT_A = "11111111-2222-3333-4444-555555555555";
    private static final String TENANT_B = "99999999-8888-7777-6666-555555555555";
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

    private OAuthClient client(String tenant, Set<String> allowed) {
        ProviderConfig cfg = ProviderConfig.microsoft(CLIENT, "s", Support.REDIRECT, tenant, allowed).toBuilder()
                .authorizationEndpoint(idp.base + "/authorize")
                .tokenEndpoint(idp.base + "/token")
                .jwksUri(idp.base + "/jwks")
                .build();
        return Support.client(clock, new InMemoryStateStore(clock), cfg);
    }

    private ExternalIdentity login(OAuthClient c, Map<String, Object> overrides) {
        Support.Started s = Support.start(c, "microsoft");
        Map<String, Object> claims = idp.idClaims(CLIENT, "ms-sub", s.nonce());
        claims.put("iss", "https://login.microsoftonline.com/" + TENANT_A + "/v2.0");
        claims.put("tid", TENANT_A);
        claims.putAll(overrides);
        idp.respondTokens(idp.sign(claims), "at");
        return c.complete(Support.cb(s.state(), "c"), s.binding());
    }

    @Test
    @DisplayName("SSO-T-oauth-122 Microsoft multi-tenant token accepted when issuer matches the tid")
    void matchingTid() {
        ExternalIdentity id = login(client("common", Set.of()), Map.of());
        assertEquals("ms-sub", id.subject());
        assertEquals(TENANT_A, id.claims().get("tid"));
    }

    @Test
    @DisplayName("SSO-T-oauth-123 Microsoft tid and issuer mismatch rejected")
    void tidIssuerMismatch() {
        assertEquals("jwt.issuer_mismatch", codeOf(() -> login(client("common", Set.of()), Map.of("tid", TENANT_B))));
    }

    @Test
    @DisplayName("SSO-T-oauth-124 Microsoft token whose issuer is another tenant but tid claims allowed tenant rejected")
    void tenantSpoof() {
        OAuthClient c = client("common", Set.of(TENANT_A));
        assertEquals("jwt.issuer_mismatch",
                codeOf(() -> login(c, Map.of("iss", "https://login.microsoftonline.com/" + TENANT_B + "/v2.0"))));
    }

    @Test
    @DisplayName("SSO-T-oauth-125 Microsoft tenant outside the allowed list rejected")
    void notAllowed() {
        OAuthClient c = client("organizations", Set.of(TENANT_B));
        assertEquals("oauth.tenant_not_allowed", codeOf(() -> login(c, Map.of())));
    }

    @Test
    @DisplayName("SSO-T-oauth-126 Microsoft allowed tenant list accepts a listed tenant")
    void allowed() {
        assertEquals("ms-sub", login(client("organizations", Set.of(TENANT_A, TENANT_B)), Map.of()).subject());
    }

    @Test
    @DisplayName("SSO-T-oauth-127 Microsoft single-tenant config pins the tenant automatically")
    void pinned() {
        assertEquals(Set.of(TENANT_B), ProviderConfig.microsoft(CLIENT, "s", Support.REDIRECT, TENANT_B, null).allowedTenants());
        OAuthClient c = client(TENANT_B, null);
        assertEquals("oauth.tenant_not_allowed", codeOf(() -> login(c, Map.of())));
    }

    @Test
    @DisplayName("SSO-T-oauth-128 Microsoft missing or malformed tid rejected")
    void badTid() {
        OAuthClient c = client("common", Set.of());
        assertEquals("oauth.tenant_invalid", codeOf(() -> login(c, Map.of("tid", "../evil"))));
        assertEquals("oauth.tenant_invalid", codeOf(() -> login(c, Map.of("tid", "{tid}"))));
        Support.Started s = Support.start(c, "microsoft");
        Map<String, Object> claims = idp.idClaims(CLIENT, "ms-sub", s.nonce());
        idp.respondTokens(idp.sign(claims), "at");
        assertEquals("oauth.tenant_missing", codeOf(() -> c.complete(Support.cb(s.state(), "c"), s.binding())));
    }

    @Test
    @DisplayName("SSO-T-oauth-129 Microsoft email without verification proof is not verified")
    void emailUnverified() {
        ExternalIdentity id = login(client("common", Set.of()), Map.of("email_verified", false));
        assertEquals("user@example.com", id.email());
        assertFalse(id.emailVerified());
    }

    @Test
    @DisplayName("SSO-T-oauth-130 Microsoft xms_edov marks the email domain owner verified")
    void emailEdov() {
        ExternalIdentity id = login(client("common", Set.of()), Map.of("xms_edov", true));
        assertTrue(id.emailVerified());
    }

    @Test
    @DisplayName("SSO-T-oauth-131 Microsoft preferred_username is never used as email")
    void preferredUsername() {
        Map<String, Object> o = new java.util.LinkedHashMap<>();
        o.put("preferred_username", "boss@corp.example");
        o.put("email", null);
        ExternalIdentity id = login(client("common", Set.of()), o);
        assertEquals(null, id.email());
    }

    @Test
    @DisplayName("SSO-T-oauth-132 Microsoft preset endpoints use the configured tenant")
    void presetEndpoints() {
        ProviderConfig p = ProviderConfig.microsoft(CLIENT, "s", Support.REDIRECT, "organizations", null);
        assertEquals("https://login.microsoftonline.com/organizations/oauth2/v2.0/authorize", p.authorizationEndpoint());
        assertEquals(ProviderConfig.MICROSOFT_ISSUER_TEMPLATE, p.issuerTemplate());
        assertEquals("oauth.config_invalid", codeOf(() -> ProviderConfig.microsoft(CLIENT, "s", Support.REDIRECT, "a/b", null)));
    }
}
